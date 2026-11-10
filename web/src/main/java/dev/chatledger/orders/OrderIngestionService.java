package dev.chatledger.orders;

import dev.chatledger.extraction.OrderParseContext;
import dev.chatledger.extraction.ParsedOrder;
import dev.chatledger.ingestion.InputChecks;
import dev.chatledger.ingestion.OrderDecider;
import dev.chatledger.ingestion.OrderPipeline;
import dev.chatledger.ingestion.RejectReason;
import dev.chatledger.matching.MatchTrigger;
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
    private final MatchTrigger matcher;
    private final dev.chatledger.flags.SuspicionWriter suspicion;

    public OrderIngestionService(TenantRepository tenants, CatalogRepository catalog, OrderRepository orders,
                                 RateLimits limits, PhoneKeys phoneKeys, IngestionEvents events, MatchTrigger matcher,
                                 dev.chatledger.flags.SuspicionWriter suspicion) {
        this.suspicion = java.util.Objects.requireNonNull(suspicion, "suspicion");
        this.tenants = tenants;
        this.catalog = catalog;
        this.orders = orders;
        this.limits = limits;
        this.phoneKeys = phoneKeys;
        this.events = events;
        this.matcher = matcher;
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

        TenantRepository.Intake intake = tenants.intake(tenantId)
                .orElseThrow(() -> new IllegalStateException("unknown or disabled tenant"));   // fail closed
        if (intake.full()) {
            // A demo workspace at its row cap (task 9, D12). The database's trigger refuses it too; this answers with a sentence.
            events.rejected(tenantId, RejectReason.DEMO_WORKSPACE_FULL);
            return IngestionResult.rejected(RejectReason.DEMO_WORKSPACE_FULL);
        }
        TenantMoney money = intake.money();
        OrderParseContext context = new OrderParseContext(money.currency(), money.scale());
        OrderPipeline.Result result = OrderPipeline.run(text, context, keys -> catalog.prices(tenantId, keys));
        OrderDecider.Draft draft = result.draft();
        ParsedOrder parsed = result.parsed();
        if (intake.demo() && parsed != null && parsed.phone() != null && !parsed.phone().inSyntheticRange()) {
            // A demo workspace takes made-up numbers only (requirement 7.3, task 9, D11).
            events.rejected(tenantId, RejectReason.REAL_PHONE_IN_DEMO);
            return IngestionResult.rejected(RejectReason.REAL_PHONE_IN_DEMO);
        }

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

        String parsedName = parsed == null ? null : parsed.customerName();
        // The order rule runs in the order's own transaction: the order is never visible without its flag (task 7, D5).
        Optional<UUID> id = orders.insert(tenantId, toStore, (c, orderId, customer) -> suspicion.orderWithin(c, tenantId, orderId, parsedName, customer));
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
        if (outcome == IngestionResult.Outcome.CREATED) {
            // After the commit, and best effort: a failure here is logged by the matcher and never changes what this method returns.
            try {
                matcher.orderStored(tenantId, id.get());
            } catch (RuntimeException ignored) {
                // the matcher logs its own failures; the order stays stored and unmatched
            }
        }
        return IngestionResult.created(outcome, id.get(), draft.reason());
    }

    private IngestionResult duplicate(UUID tenantId, OrderRepository.Stored stored, String text) {
        boolean differentText = !stored.rawText().equals(text);
        events.duplicate(tenantId, stored.id(), differentText);
        return IngestionResult.duplicate(differentText, stored.id());
    }
}
