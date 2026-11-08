package dev.chatledger.payments;

import dev.chatledger.extraction.OrderParseContext;
import dev.chatledger.extraction.ParsedPayment;
import dev.chatledger.extraction.PaymentLimits;
import dev.chatledger.extraction.PaymentTemplates;
import dev.chatledger.ingestion.InputChecks;
import dev.chatledger.ingestion.PaymentDecider;
import dev.chatledger.ingestion.PaymentPipeline;
import dev.chatledger.ingestion.RejectReason;
import dev.chatledger.matching.MatchTrigger;
import dev.chatledger.orders.PhoneKeys;
import dev.chatledger.orders.TenantMoney;
import dev.chatledger.orders.TenantRepository;
import dev.chatledger.security.RateDecision;
import dev.chatledger.security.RateLimits;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a confirmation text into a stored payment. The single entry point for payment text: the paste form (task 8) and the
 * signed machine API (task 11) will call it, so the defenses below apply to both.
 *
 * <ol>
 *   <li>Re-check the input ({@link InputChecks}, with the payment size cap); refuse, never repair.</li>
 *   <li>Rate-limit by tenant (the same budget as orders); the tenant is a parameter from the caller's principal, never from the text.</li>
 *   <li>Idempotency: an existing payment with the same external id is returned, never overwritten.</li>
 *   <li>Parse with the proven templates, validate, decide the status ({@link PaymentPipeline}); the tenant's currency and scale
 *       come from its own row.</li>
 *   <li>Duplicate detection: a transaction id already recorded for this tenant and provider makes the new record a {@code DUPLICATE}
 *       linked to the first, never a second {@code RECEIVED}; the partial unique index decides races.</li>
 *   <li>Store the payment and its audit row in one transaction. The sender's phone is stored only as a keyed hash and its last four
 *       digits ({@link PhoneKeys}, the same key as customers); no customer is created from an SMS.</li>
 * </ol>
 *
 * <p>Nothing from the text is ever logged: the log takes ids and codes only ({@link PaymentEvents}).
 */
@Service
public class PaymentIngestionService {

    private final TenantRepository tenants;
    private final PaymentRepository payments;
    private final PaymentTemplates templates;
    private final RateLimits limits;
    private final PhoneKeys phoneKeys;
    private final PaymentEvents events;
    private final Clock clock;
    private final MatchTrigger matcher;

    public PaymentIngestionService(TenantRepository tenants, PaymentRepository payments, PaymentTemplates templates,
                                   RateLimits limits, PhoneKeys phoneKeys, PaymentEvents events, Clock clock, MatchTrigger matcher) {
        this.tenants = tenants;
        this.payments = payments;
        this.templates = templates;
        this.limits = limits;
        this.phoneKeys = phoneKeys;
        this.events = events;
        this.clock = clock;
        this.matcher = matcher;
    }

    /**
     * @param tenantId   The tenant, from the caller's authenticated principal (or the verified signing key).
     * @param actor      The signed-in user, or null for a machine caller.
     * @param externalId The caller's id for the message; one payment per (tenant, external id).
     * @param text       The confirmation text, exactly as received.
     */
    public PaymentIngestionResult ingest(UUID tenantId, UUID actor, String externalId, String text) {
        Optional<RejectReason> bad = InputChecks.check(externalId, text, PaymentLimits.MAX_SMS_CHARS);
        if (bad.isPresent()) {
            events.rejected(tenantId, bad.get());
            return new PaymentIngestionResult(PaymentIngestionResult.Outcome.REJECTED_INPUT, null, null, null, bad.get(), 0);
        }
        RateDecision decision = limits.ingestion(tenantId);
        if (!decision.allowed()) {
            events.rateLimited(tenantId);
            return new PaymentIngestionResult(PaymentIngestionResult.Outcome.RATE_LIMITED, null, null, null, null,
                    decision.retryAfterSeconds());
        }
        Optional<PaymentRepository.Stored> existing = payments.findByExternalId(tenantId, externalId);
        if (existing.isPresent()) {
            return replay(tenantId, existing.get(), text);
        }

        TenantMoney money = tenants.money(tenantId)
                .orElseThrow(() -> new IllegalStateException("unknown or disabled tenant"));   // fail closed
        OrderParseContext context = new OrderParseContext(money.currency(), money.scale());
        PaymentDecider.Draft draft = PaymentPipeline.run(text, templates, context, clock.instant());
        ParsedPayment parsed = draft.parsed();

        String phoneHash = null;
        if (parsed != null && parsed.senderPhone() != null) {
            phoneHash = phoneKeys.hash(tenantId, parsed.senderPhone());
        }

        // A repeated transaction id is a DUPLICATE linked to the first payment. The unique index decides a race: a request that
        // loses it gets no row back and tries once more as a duplicate.
        UUID duplicateOf = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (parsed != null && parsed.transactionId() != null) {
                duplicateOf = payments.findOriginal(tenantId, parsed.provider(), parsed.transactionId()).orElse(null);
            }
            PaymentRepository.NewPayment toStore = build(externalId, text, draft, parsed, phoneHash, duplicateOf, actor, money.currency());
            Optional<UUID> id = payments.insert(tenantId, toStore);
            if (id.isPresent()) {
                PaymentIngestionResult.Outcome outcome = outcome(toStore.status());
                events.created(tenantId, id.get(), outcome, draft.reason(), parsed == null ? null : parsed.templateId());
                if (outcome == PaymentIngestionResult.Outcome.CREATED || outcome == PaymentIngestionResult.Outcome.CREATED_UNVERIFIED) {
                    // After the commit, and best effort: a failure here is logged by the matcher and never changes what this method returns.
                    try {
                        matcher.paymentStored(tenantId, id.get());
                    } catch (RuntimeException ignored) {
                        // the matcher logs its own failures; the payment stays stored and unmatched
                    }
                }
                return new PaymentIngestionResult(outcome, id.get(), duplicateOf, draft.reason(), null, 0);
            }
            Optional<PaymentRepository.Stored> winner = payments.findByExternalId(tenantId, externalId);
            if (winner.isPresent()) {
                return replay(tenantId, winner.get(), text);
            }
        }
        throw new IllegalStateException("payment could not be stored after a conflict");
    }

    private static PaymentRepository.NewPayment build(String externalId, String text, PaymentDecider.Draft draft, ParsedPayment p,
                                                      String phoneHash, UUID duplicateOf, UUID actor, String tenantCurrency) {
        if (p == null) {
            // Nothing was read: only the text, the status and the tenant's currency are stored.
            return new PaymentRepository.NewPayment(externalId, "UNPARSED", null, null, null, null, tenantCurrency, null, null,
                    null, null, null, text, null, actor);
        }
        String status = duplicateOf != null ? "DUPLICATE" : draft.status().name();
        return new PaymentRepository.NewPayment(externalId, status, p.templateId(), p.provider(), p.transactionId(), p.amountMinor(),
                p.currency(), p.senderName(), phoneHash, p.senderLast4(), p.reference(), p.paidAt(), text, duplicateOf, actor);
    }

    private static PaymentIngestionResult.Outcome outcome(String status) {
        return switch (status) {
            case "RECEIVED" -> PaymentIngestionResult.Outcome.CREATED;
            case "UNVERIFIED" -> PaymentIngestionResult.Outcome.CREATED_UNVERIFIED;
            case "DUPLICATE" -> PaymentIngestionResult.Outcome.CREATED_DUPLICATE_TX;
            default -> PaymentIngestionResult.Outcome.CREATED_UNPARSED;
        };
    }

    private PaymentIngestionResult replay(UUID tenantId, PaymentRepository.Stored stored, String text) {
        boolean differentText = !stored.rawText().equals(text);
        events.replay(tenantId, stored.id(), differentText);
        return new PaymentIngestionResult(differentText ? PaymentIngestionResult.Outcome.REPLAY_DIFFERENT_TEXT
                : PaymentIngestionResult.Outcome.REPLAY, stored.id(), null, null, null, 0);
    }
}
