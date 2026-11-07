package dev.chatledger.orders;

import dev.chatledger.extraction.OrderParseContext;
import dev.chatledger.extraction.ParsedOrder;
import dev.chatledger.ingestion.InputChecks;
import dev.chatledger.ingestion.OrderDecider;
import dev.chatledger.ingestion.OrderPipeline;
import dev.chatledger.ingestion.RejectReason;
import dev.chatledger.security.RateDecision;
import dev.chatledger.security.RateLimits;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Turns a chat message into a stored order. This is the single entry point for order text: the paste form (task 8) and
 * the signed machine API (task 11) will call it, so the defenses below apply to both.
 *
 * <ol>
 *   <li>Re-check the input ({@link InputChecks}); refuse, never repair.</li>
 *   <li>Rate-limit by tenant (the tenant is a parameter taken from the caller's principal, never from the message).</li>
 *   <li>Idempotency: an existing order with the same external id is returned, never overwritten.</li>
 *   <li>Parse, validate, look up the catalog, decide ({@link OrderPipeline}); the tenant's currency and scale come from
 *       the tenant's own row, not from the message.</li>
 *   <li>Store the customer, order, lines and audit row in one transaction.</li>
 * </ol>
 *
 * <p>Nothing from the message is ever logged: the log takes ids and codes only ({@link IngestionEvents}).
 */
@Service
public class OrderIngestionService {

    private final TenantRepository tenants;
    private final CatalogRepository catalog;
    private final OrderRepository orders;
    private final RateLimits limits;
    private final PhoneKeys phoneKeys;
    private final IngestionEvents events;

    public OrderIngestionService(TenantRepository tenants, CatalogRepository catalog, OrderRepository orders,
                                 RateLimits limits, PhoneKeys phoneKeys, IngestionEvents events) {
        this.tenants = tenants;
        this.catalog = catalog;
        this.orders = orders;
        this.limits = limits;
        this.phoneKeys = phoneKeys;
        this.events = events;
    }

    /**
     * @param tenantId   The tenant, from the caller's authenticated principal (or the verified signing key).
     * @param actor      The signed-in user, or null for a machine caller.
     * @param externalId The caller's id for the message; one order per (tenant, external id).
     * @param text       The message, exactly as received.
     */
    public IngestionResult ingest(UUID tenantId, UUID actor, String externalId, String text) {
        Optional<RejectReason> bad = InputChecks.check(externalId, text);
        if (bad.isPresent()) {
            events.rejected(tenantId, bad.get());
            return IngestionResult.rejected(bad.get());
        }
        RateDecision decision = limits.ingestion(tenantId);
        if (!decision.allowed()) {
            events.rateLimited(tenantId);
            return IngestionResult.rateLimited(decision.retryAfterSeconds());
        }

        Optional<OrderRepository.Stored> existing = orders.findByExternalId(tenantId, externalId);
        if (existing.isPresent()) {
            return duplicate(tenantId, existing.get(), text);
        }

        TenantMoney money = tenants.money(tenantId)
                .orElseThrow(() -> new IllegalStateException("unknown or disabled tenant"));   // fail closed
        OrderParseContext context = new OrderParseContext(money.currency(), money.scale());
        OrderPipeline.Result result = OrderPipeline.run(text, context, keys -> catalog.prices(tenantId, keys));
        OrderDecider.Draft draft = result.draft();
        ParsedOrder parsed = result.parsed();

        String phoneHash = null;
        String phoneLast4 = null;
        if (parsed != null && parsed.phone() != null) {
            phoneHash = phoneKeys.hash(tenantId, parsed.phone());
            phoneLast4 = parsed.phone().last4();
        }
        OrderRepository.NewOrder toStore = new OrderRepository.NewOrder(externalId,
                parsed == null ? null : parsed.reference(), text, draft,
                parsed == null ? null : parsed.customerName(), phoneHash, phoneLast4,
                parsed == null ? null : parsed.deliveryNote(), actor);

        Optional<UUID> id = orders.insert(tenantId, toStore);
        if (id.isEmpty()) {
            // Another request with the same external id won the race: the unique key decided, nothing was written.
            OrderRepository.Stored winner = orders.findByExternalId(tenantId, externalId)
                    .orElseThrow(() -> new IllegalStateException("order vanished after a conflict"));
            return duplicate(tenantId, winner, text);
        }
        IngestionResult.Outcome outcome = switch (draft.status()) {
            case PENDING_PAYMENT -> IngestionResult.Outcome.CREATED;
            case NEEDS_REVIEW -> IngestionResult.Outcome.CREATED_NEEDS_REVIEW;
            case UNPARSED -> IngestionResult.Outcome.CREATED_UNPARSED;
        };
        events.created(tenantId, id.get(), outcome, draft.reason(), draft.lines().size());
        return IngestionResult.created(outcome, id.get(), draft.reason());
    }

    private IngestionResult duplicate(UUID tenantId, OrderRepository.Stored stored, String text) {
        boolean differentText = !stored.rawText().equals(text);
        events.duplicate(tenantId, stored.id(), differentText);
        return IngestionResult.duplicate(differentText, stored.id());
    }
}
