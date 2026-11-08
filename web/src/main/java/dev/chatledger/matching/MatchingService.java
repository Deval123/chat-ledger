package dev.chatledger.matching;

import dev.chatledger.ledger.LedgerException;
import dev.chatledger.ledger.LedgerTransaction;
import dev.chatledger.ledger.jdbc.JdbcLedgerRepository;
import dev.chatledger.security.AuditLogRepository;
import dev.chatledger.tenancy.TenantScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Matches payments to orders. Two parts.
 *
 * <p><b>The engine</b> ({@link #onPaymentRecorded}, {@link #onOrderRecorded}, and the {@link MatchTrigger} calls the ingestion services
 * make after they commit) scores the open candidates with the pure scorer, turns the scores into a plan, and either applies an automatic
 * match or stores suggestions. A suggestion changes nothing else: no posting, no status.
 *
 * <p><b>{@link #apply}</b> is the only place that creates an active match, and it is <b>one database transaction</b>:
 * <ol>
 *   <li>lock the payment, then the order (always in that order, so two operations cannot deadlock);</li>
 *   <li>re-read everything under the lock and refuse if the payment is no longer free, the order no longer open, or nothing is left to pay;
 *       an automatic match is re-scored on the locked data and becomes a suggestion if it no longer qualifies;</li>
 *   <li>compute what is applied ({@code min(payment, balance)}) and the excess from the locked balance;</li>
 *   <li>make sure the four accounts and the order's {@code ORDER_ACCEPTED} posting exist;</li>
 *   <li>post the payment (debit clearing; credit receivable; credit customer credit for an excess) through
 *       {@link JdbcLedgerRepository#appendWithin}, write the match, move the order and payment statuses, retire the other open
 *       suggestions, append the audit row;</li>
 *   <li>commit: the deferred database triggers re-check the sums, the statuses and the posting.</li>
 * </ol>
 * Any failure at any step, including a refusal by those triggers, rolls everything back: no match, no posting, no status change, no
 * audit row. The payment stays free and the order open, and the unique indexes make a retry safe.
 *
 * <p><b>The seller's decisions</b> ({@link #confirm}, {@link #reject}, {@link #matchManually}, {@link #unmatch}, {@link #markUnmatched},
 * {@link #reopen}, {@link #rematch}) are each one transaction too, with the same lock order (payment, order, match), and each begins by
 * asking the database whether the user is an enabled seller of the tenant. An unmatch posts the reversal of the match's posting, never
 * an edit. The seller has the last word: a pair the seller rejected or reversed is never matched automatically again.
 *
 * <p>Who recorded a payment (the {@link Recorder}) is read from the append-only audit trail of its ingestion, never from a caller.
 */
@Service
public class MatchingService implements MatchTrigger {

    /** How a match comes to be: by the engine, or by the seller's decision. */
    public enum Mode { AUTO, CONFIRM }

    public enum Outcome { APPLIED, DEGRADED_TO_SUGGESTION, NOT_APPLICABLE }

    /**
     * Why something was not done. The last five belong to the seller's actions: the user is not an enabled seller of the tenant, the
     * match is not an open suggestion, the match is not active, the seller had already said no to the pair (an automatic match never
     * overrides that), the payment is not in a status the action starts from.
     */
    public enum Reason {
        NOT_FOUND, PAYMENT_NOT_FREE, ORDER_NOT_OPEN, CURRENCY_MISMATCH, NOTHING_LEFT_TO_PAY, AUTO_NOT_ELIGIBLE,
        NOT_A_SELLER, NOT_SUGGESTED, NOT_ACTIVE, SELLER_DECLINED, PAYMENT_STATUS_NOT_ALLOWED
    }

    /** The steps of {@link #apply}, in order. A test can fail the transaction after any of them. */
    public enum ApplyStep { PAYMENT_LOCKED, LOCKED, ACCOUNTS, ORDER_POSTED, MATCH_POSTED, MATCH_WRITTEN, ORDER_STATUS, PAYMENT_STATUS, SUPERSEDED, AUDIT }

    /** The steps of the seller's actions other than a match (reverse, reject, move a payment), in order. */
    public enum ActionStep { PAYMENT_LOCKED, LOCKED, REVERSAL_POSTED, MATCH_REVERSED, ORDER_STATUS, PAYMENT_STATUS, MATCH_REJECTED, SUGGESTIONS_REJECTED, AUDIT }

    /** The same seam for {@link ActionStep}. */
    @FunctionalInterface
    public interface ActionHook {
        ActionHook NONE = (step, connection) -> {
        };

        void after(ActionStep step, Connection connection) throws SQLException;
    }

    /** What a seller's action did: done, or refused with a reason. {@code orderStatus} is set by an unmatch. Ids and codes only. */
    public record Decision(boolean done, UUID subjectId, Reason reason, String orderStatus) {
        static Decision done(UUID subjectId, String orderStatus) {
            return new Decision(true, subjectId, null, orderStatus);
        }

        static Decision refused(Reason reason) {
            return new Decision(false, null, reason, null);
        }
    }

    /** What a rematch did. {@code refusal} is null unless the user was not a seller. */
    public record RematchResult(Reason refusal, int examined, int applied, int suggested) {
    }

    /** Called after each step with the transaction's connection. Production uses {@link #NONE}; a test throws from it to prove a rollback. */
    @FunctionalInterface
    public interface StepHook {
        StepHook NONE = (step, connection) -> {
        };

        void after(ApplyStep step, Connection connection) throws SQLException;
    }

    /** What {@link #apply} did. Ids, amounts and codes only. */
    public record ApplyResult(Outcome outcome, UUID matchId, long appliedMinor, long excessMinor, String orderStatus, Reason reason) {
        static ApplyResult notApplicable(Reason reason) {
            return new ApplyResult(Outcome.NOT_APPLICABLE, null, 0, 0, null, reason);
        }
    }

    public enum RunKind { APPLIED, SUGGESTED, NOTHING, SKIPPED }

    /** What an engine run did. */
    public record Run(RunKind kind, UUID matchId, int suggestions) {
    }

    private static final Set<String> FREE_PAYMENT = Set.of("RECEIVED", "UNVERIFIED");
    private static final Set<String> OPEN_ORDER = Set.of("PENDING_PAYMENT", "PARTIALLY_PAID");

    private final DataSource dataSource;
    private final TenantScope scope;
    private final JdbcLedgerRepository ledger;
    private final MatchRepository matches;
    private final MatchConfig config;
    private final MatchEvents events;
    private final Clock clock;
    private final StepHook hook;
    private final ActionHook actions;

    @Autowired
    public MatchingService(DataSource dataSource, MatchRepository matches, MatchConfig config, MatchEvents events, Clock clock) {
        this(dataSource, matches, config, events, clock, StepHook.NONE, ActionHook.NONE);
    }

    MatchingService(DataSource dataSource, MatchRepository matches, MatchConfig config, MatchEvents events, Clock clock, StepHook hook) {
        this(dataSource, matches, config, events, clock, hook, ActionHook.NONE);
    }

    MatchingService(DataSource dataSource, MatchRepository matches, MatchConfig config, MatchEvents events, Clock clock, StepHook hook,
                    ActionHook actions) {
        this.dataSource = dataSource;
        this.scope = new TenantScope(dataSource);
        this.ledger = new JdbcLedgerRepository(scope);
        this.matches = matches;
        this.config = config;
        this.events = events;
        this.clock = clock;
        this.hook = hook;
        this.actions = actions;
    }

    // ── The engine ─────────────────────────────────────────────────────────

    @Override
    public void paymentStored(UUID tenantId, UUID paymentId) {
        try {
            onPaymentRecorded(tenantId, paymentId);
        } catch (RuntimeException e) {
            events.failed(tenantId, "payment", paymentId);   // the payment stays stored and unmatched; nothing from the failure is logged
        }
    }

    @Override
    public void orderStored(UUID tenantId, UUID orderId) {
        try {
            onOrderRecorded(tenantId, orderId);
        } catch (RuntimeException e) {
            events.failed(tenantId, "order", orderId);
        }
    }

    /** Scores the open orders against a new payment, then applies an automatic match or stores suggestions. */
    public Run onPaymentRecorded(UUID tenantId, UUID paymentId) {
        Optional<MatchRepository.PaymentCandidate> candidate = matches.paymentCandidate(tenantId, paymentId);
        if (candidate.isEmpty()) {
            return new Run(RunKind.SKIPPED, null, 0);
        }
        PaymentFacts payment = candidate.get().facts();
        Recorder recorder = candidate.get().recorder();
        Set<UUID> declined = matches.declinedOrders(tenantId, paymentId);   // the seller said no to these: never proposed or applied again
        List<ScoredPair> scored = matches.openOrders(tenantId, payment.currency(), config.candidateCap()).stream()
                .filter(order -> !declined.contains(order.id()))
                .map(order -> MatchScorer.score(order, payment, recorder, config)).toList();
        MatchPlanner.Plan plan = MatchPlanner.plan(scored, config);
        switch (plan.kind()) {
            case AUTO -> {
                ApplyResult result = apply(tenantId, plan.auto().orderId(), paymentId, Mode.AUTO, null);
                return switch (result.outcome()) {
                    case APPLIED -> new Run(RunKind.APPLIED, result.matchId(), 0);
                    case DEGRADED_TO_SUGGESTION -> new Run(RunKind.SUGGESTED, null, storeSuggestions(tenantId, List.of(plan.auto())));
                    case NOT_APPLICABLE -> new Run(RunKind.SKIPPED, null, 0);
                };
            }
            case SUGGEST -> {
                return new Run(RunKind.SUGGESTED, null, storeSuggestions(tenantId, plan.suggestions()));
            }
            default -> {
                return new Run(RunKind.NOTHING, null, 0);
            }
        }
    }

    /**
     * Scores the open payments against a new order. If the best payment would be matched automatically, the decision is taken from that
     * payment's side too (the payment may fit another order better), so an order can never take a payment that is clearer elsewhere.
     */
    public Run onOrderRecorded(UUID tenantId, UUID orderId) {
        Optional<OrderFacts> found = matches.openOrder(tenantId, orderId);
        if (found.isEmpty()) {
            return new Run(RunKind.SKIPPED, null, 0);
        }
        OrderFacts order = found.get();
        Set<UUID> declined = matches.declinedPayments(tenantId, orderId);
        List<ScoredPair> scored = matches.openPaymentCandidates(tenantId, order.currency(), config.candidateCap()).stream()
                .filter(candidate -> !declined.contains(candidate.facts().id()))
                .map(candidate -> MatchScorer.score(order, candidate.facts(), candidate.recorder(), config)).toList();
        MatchPlanner.Plan plan = MatchPlanner.plan(scored, config);
        return switch (plan.kind()) {
            case AUTO -> onPaymentRecorded(tenantId, plan.auto().paymentId());
            case SUGGEST -> new Run(RunKind.SUGGESTED, null, storeSuggestions(tenantId, plan.suggestions()));
            case NONE -> new Run(RunKind.NOTHING, null, 0);
        };
    }

    private int storeSuggestions(UUID tenantId, List<ScoredPair> pairs) {
        int stored = 0;
        for (ScoredPair pair : pairs) {
            if (matches.insertSuggestion(tenantId, pair.orderId(), pair.paymentId(), pair.score(), pair.reasonsJson()).isPresent()) {
                stored++;
            }
        }
        if (stored > 0) {
            events.suggested(tenantId, stored);
        }
        return stored;
    }

    // ── The atomic step ────────────────────────────────────────────────────

    /**
     * Applies one match in one transaction (see the class comment).
     *
     * @param mode  {@link Mode#AUTO} from the engine, which re-checks every floor on the locked data; {@link Mode#CONFIRM} for the seller's decision.
     * @param actor who decided, required for {@link Mode#CONFIRM} and null for {@link Mode#AUTO}. For {@code CONFIRM} the database is asked
     *              whether the actor is an enabled seller of the tenant; the answer is {@link Reason#NOT_A_SELLER} otherwise.
     */
    public ApplyResult apply(UUID tenantId, UUID orderId, UUID paymentId, Mode mode, UUID actor) {
        // (a missing tenant is refused by name by TenantScope, so it is not checked a second time here)
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(mode, "mode");
        if ((mode == Mode.CONFIRM) != (actor != null)) {
            throw new IllegalArgumentException("a confirmation names who decided; an automatic match names nobody");
        }
        ApplyResult result = inTransaction(tenantId, c -> mode == Mode.CONFIRM && !matches.isSellerWithin(c, tenantId, actor)
                ? ApplyResult.notApplicable(Reason.NOT_A_SELLER)
                : applyWithin(c, tenantId, orderId, paymentId, mode, actor, null));
        switch (result.outcome()) {
            case APPLIED -> events.applied(tenantId, result.matchId(), mode, result.appliedMinor(), result.excessMinor(), result.orderStatus());
            case DEGRADED_TO_SUGGESTION -> events.degraded(tenantId, orderId, paymentId);
            case NOT_APPLICABLE -> events.notApplicable(tenantId, orderId, paymentId, result.reason());
        }
        return result;
    }

    /**
     * The seller confirms a suggestion. Everything is decided inside one transaction on the locked data: the suggestion must still be
     * open (a suggestion rejected or retired meanwhile is {@link Reason#NOT_SUGGESTED}, never turned into a match by the back door), the
     * payment still free, the order still open with something to pay; the amounts are computed now, not taken from when it was suggested.
     */
    public ApplyResult confirm(UUID tenantId, UUID matchId, UUID actor) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(actor, "actor");
        ApplyResult result = inTransaction(tenantId, c -> {
            if (!matches.isSellerWithin(c, tenantId, actor)) {
                return ApplyResult.notApplicable(Reason.NOT_A_SELLER);
            }
            Optional<MatchRepository.MatchRow> row = matches.matchWithin(c, tenantId, matchId);
            return row.isEmpty() ? ApplyResult.notApplicable(Reason.NOT_FOUND)
                    : applyWithin(c, tenantId, row.get().orderId(), row.get().paymentId(), Mode.CONFIRM, actor, matchId);
        });
        if (result.outcome() == Outcome.APPLIED) {
            events.applied(tenantId, result.matchId(), Mode.CONFIRM, result.appliedMinor(), result.excessMinor(), result.orderStatus());
        } else {
            events.refused(tenantId, "CONFIRM", matchId, result.reason());
        }
        return result;
    }

    /**
     * The seller matches a payment to an order of their choice, whether or not the engine proposed it. If the engine had proposed the
     * pair, that suggestion is the one confirmed; otherwise a new confirmed match is made. A pair the seller rejected before can be
     * matched this way: only an automatic match is barred from overriding a rejection.
     */
    public ApplyResult matchManually(UUID tenantId, UUID orderId, UUID paymentId, UUID actor) {
        Objects.requireNonNull(actor, "actor");
        return apply(tenantId, orderId, paymentId, Mode.CONFIRM, actor);
    }

    private ApplyResult inTransaction(UUID tenantId, TenantScope.SqlWork<ApplyResult> work) {
        try {
            return scope.call(tenantId, work);
        } catch (SQLException | LedgerException e) {
            throw new IllegalStateException("Could not apply a match", e);
        }
    }

    private ApplyResult applyWithin(Connection c, UUID tenantId, UUID orderId, UUID paymentId, Mode mode, UUID actor, UUID requiredSuggestion)
            throws SQLException {
        // 1. Lock the payment, then the order. Never the other way round.
        Optional<MatchRepository.LockedPayment> lockedPayment = matches.lockPayment(c, tenantId, paymentId);
        hook.after(ApplyStep.PAYMENT_LOCKED, c);
        Optional<MatchRepository.LockedOrder> lockedOrder = lockedPayment.isPresent() ? matches.lockOrder(c, tenantId, orderId) : Optional.empty();
        if (lockedOrder.isEmpty()) {   // an order is only looked for when the payment exists, so this covers a missing payment too
            return ApplyResult.notApplicable(Reason.NOT_FOUND);
        }
        hook.after(ApplyStep.LOCKED, c);

        // 2. Re-read everything under the lock. (A payment that is free, and an order that is open, always have an amount and a total:
        //    the CHECK constraints chk_payment_by_status and chk_orders_total_by_status say so, so they are not tested again here.)
        MatchRepository.LockedPayment pay = lockedPayment.orElseThrow();
        MatchRepository.LockedOrder ord = lockedOrder.get();
        if (!FREE_PAYMENT.contains(pay.status())) {
            return ApplyResult.notApplicable(Reason.PAYMENT_NOT_FREE);
        }
        if (!OPEN_ORDER.contains(ord.status())) {
            return ApplyResult.notApplicable(Reason.ORDER_NOT_OPEN);
        }
        if (!pay.currency().equals(ord.currency())) {
            return ApplyResult.notApplicable(Reason.CURRENCY_MISMATCH);
        }
        long total = ord.totalMinor();
        long alreadyApplied = matches.appliedWithin(c, tenantId, orderId);
        long balance = total - alreadyApplied;
        if (balance <= 0) {
            return ApplyResult.notApplicable(Reason.NOTHING_LEFT_TO_PAY);
        }
        Optional<UUID> suggestion = matches.suggestionWithin(c, tenantId, orderId, paymentId);
        if (requiredSuggestion != null && !suggestion.equals(Optional.of(requiredSuggestion))) {
            return ApplyResult.notApplicable(Reason.NOT_SUGGESTED);
        }
        if (mode == Mode.AUTO && matches.wasDeclinedWithin(c, tenantId, orderId, paymentId)) {
            return ApplyResult.notApplicable(Reason.SELLER_DECLINED);   // the seller has the last word: an automatic match never overrides a no
        }
        OrderFacts orderFacts = new OrderFacts(ord.id(), total, alreadyApplied, ord.currency(), ord.createdAt(), ord.orderRef(), ord.customerName(),
                ord.customerPhoneHash(), ord.customerLast4(), matches.referenceSharedWithin(c, tenantId, ord.orderRef()));
        PaymentFacts paymentFacts = new PaymentFacts(pay.id(), pay.amountMinor(), pay.currency(), pay.hasTransactionId(), pay.reference(),
                pay.senderName(), pay.senderPhoneHash(), pay.senderLast4(), pay.paidAt());
        ScoredPair scored = MatchScorer.score(orderFacts, paymentFacts, pay.recorder(), config);
        if (mode == Mode.AUTO && !scored.autoEligible()) {
            return new ApplyResult(Outcome.DEGRADED_TO_SUGGESTION, null, 0, 0, null, Reason.AUTO_NOT_ELIGIBLE);
        }

        // 3. What is applied and what is over.
        long applied = Math.min(pay.amountMinor(), balance);
        long excess = pay.amountMinor() - applied;
        Instant now = clock.instant();

        // 4. Accounts and the order's own posting.
        ChartOfAccounts.Accounts accounts = ChartOfAccounts.ensure(c, tenantId);
        hook.after(ApplyStep.ACCOUNTS, c);
        if (!matches.orderPostedWithin(c, tenantId, orderId)) {
            ledger.appendWithin(c, Postings.orderAccepted(tenantId, orderId, total, accounts, now));
        }
        hook.after(ApplyStep.ORDER_POSTED, c);

        // 5. The posting, the match, the statuses, the other suggestions, the audit row.
        boolean confirmSuggestion = mode == Mode.CONFIRM && suggestion.isPresent();
        if (suggestion.isPresent() && !confirmSuggestion) {
            expectOne(matches.supersedeWithin(c, tenantId, suggestion.get(), now));   // an automatic match replaces its own earlier suggestion
        }
        UUID matchId = confirmSuggestion ? suggestion.get() : UUID.randomUUID();
        LedgerTransaction posting = Postings.paymentMatched(tenantId, matchId, applied, excess, accounts, now);
        ledger.appendWithin(c, posting);
        hook.after(ApplyStep.MATCH_POSTED, c);

        if (confirmSuggestion) {
            expectOne(matches.confirmSuggestionWithin(c, tenantId, matchId, actor, now, applied, excess, posting.id()));
        } else {
            boolean auto = mode == Mode.AUTO;
            expectOne(matches.insertActiveWithin(c, tenantId, new MatchRepository.NewActive(matchId, orderId, paymentId, auto ? "AUTO" : "CONFIRMED",
                    applied, excess, scored.score(), scored.reasonsJson(), posting.id(), actor, auto ? null : now)));   // actor is null for AUTO (checked at the top)
        }
        hook.after(ApplyStep.MATCH_WRITTEN, c);

        String orderStatus = alreadyApplied + applied >= total ? "PAID" : "PARTIALLY_PAID";
        expectOne(matches.setOrderStatusWithin(c, tenantId, orderId, orderStatus));
        hook.after(ApplyStep.ORDER_STATUS, c);
        expectOne(matches.setPaymentStatusWithin(c, tenantId, paymentId, "MATCHED"));
        hook.after(ApplyStep.PAYMENT_STATUS, c);

        matches.supersedeForPaymentWithin(c, tenantId, paymentId, matchId, now);
        if ("PAID".equals(orderStatus)) {
            matches.supersedeForOrderWithin(c, tenantId, orderId, matchId, now);
        }
        hook.after(ApplyStep.SUPERSEDED, c);

        String action = mode == Mode.AUTO ? "MATCH_AUTO" : confirmSuggestion ? "MATCH_CONFIRMED" : "MATCH_MANUAL";
        AuditLogRepository.appendWithin(c, tenantId, actor, action, matchId.toString());
        hook.after(ApplyStep.AUDIT, c);
        return new ApplyResult(Outcome.APPLIED, matchId, applied, excess, orderStatus, null);
    }

    // ── The seller's other decisions ───────────────────────────────────────
    //
    // Every one is a single transaction and asks the database, first, whether the user is an enabled seller of the tenant. Locks are taken
    // in the one order used everywhere: payment, then order, then match. (A rejection locks the match alone, which cannot deadlock: it
    // holds that one lock and waits for no other.)

    private static final Set<String> ACTIVE_MATCH = Set.of("AUTO", "CONFIRMED");

    /** The seller rejects a suggestion. The pair is remembered: the engine does not propose or apply it again. */
    public Decision reject(UUID tenantId, UUID matchId, UUID actor) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(actor, "actor");
        Decision decision = decide(tenantId, c -> {
            if (!matches.isSellerWithin(c, tenantId, actor)) {
                return Decision.refused(Reason.NOT_A_SELLER);
            }
            Optional<MatchRepository.MatchRow> row = matches.lockMatchWithin(c, tenantId, matchId);
            if (row.isEmpty()) {
                return Decision.refused(Reason.NOT_FOUND);
            }
            actions.after(ActionStep.LOCKED, c);
            if (!"SUGGESTED".equals(row.get().state())) {
                return Decision.refused(Reason.NOT_SUGGESTED);
            }
            expectOne(matches.rejectWithin(c, tenantId, matchId, actor, clock.instant()));
            actions.after(ActionStep.MATCH_REJECTED, c);
            AuditLogRepository.appendWithin(c, tenantId, actor, "MATCH_REJECTED", matchId.toString());
            actions.after(ActionStep.AUDIT, c);
            return Decision.done(matchId, null);
        });
        if (decision.done()) {
            events.rejected(tenantId, matchId);
        } else {
            events.refused(tenantId, "REJECT", matchId, decision.reason());
        }
        return decision;
    }

    /**
     * The seller undoes an active match: the posting is reversed (a new transaction that mirrors it, the original untouched), the match
     * becomes {@code REVERSED} with who and when, the order status is recomputed from its remaining matches and the payment is free
     * again. The pair is remembered as declined. All in one transaction; a second unmatch of the same match is refused.
     */
    public Decision unmatch(UUID tenantId, UUID matchId, UUID actor) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(actor, "actor");
        Decision decision = decide(tenantId, c -> unmatchWithin(c, tenantId, matchId, actor));
        if (decision.done()) {
            events.reversed(tenantId, matchId, decision.orderStatus());
        } else {
            events.refused(tenantId, "UNMATCH", matchId, decision.reason());
        }
        return decision;
    }

    private Decision unmatchWithin(Connection c, UUID tenantId, UUID matchId, UUID actor) throws SQLException {
        if (!matches.isSellerWithin(c, tenantId, actor)) {
            return Decision.refused(Reason.NOT_A_SELLER);
        }
        Optional<MatchRepository.MatchRow> hint = matches.matchWithin(c, tenantId, matchId);   // only to learn what to lock first
        if (hint.isEmpty()) {
            return Decision.refused(Reason.NOT_FOUND);
        }
        MatchRepository.LockedPayment pay = matches.lockPayment(c, tenantId, hint.get().paymentId()).orElseThrow();
        actions.after(ActionStep.PAYMENT_LOCKED, c);
        matches.lockOrder(c, tenantId, hint.get().orderId()).orElseThrow();
        MatchRepository.MatchRow match = matches.lockMatchWithin(c, tenantId, matchId).orElseThrow();   // read again, now that the locks are held
        actions.after(ActionStep.LOCKED, c);
        if (!ACTIVE_MATCH.contains(match.state())) {
            return Decision.refused(Reason.NOT_ACTIVE);
        }
        Instant now = clock.instant();
        ledger.reverseWithin(c, tenantId, match.ledgerTxnId(), UUID.randomUUID(), now);
        actions.after(ActionStep.REVERSAL_POSTED, c);
        expectOne(matches.reverseMatchWithin(c, tenantId, matchId, actor, now));
        actions.after(ActionStep.MATCH_REVERSED, c);
        // The order was consistent, so its active matches added up to at most its total; one of them, of at least 1, is gone: what is left
        // is below the total, and the order is either untouched or partly paid. It cannot be paid.
        long stillApplied = matches.appliedWithin(c, tenantId, match.orderId());
        String orderStatus = stillApplied == 0 ? "PENDING_PAYMENT" : "PARTIALLY_PAID";
        expectOne(matches.setOrderStatusWithin(c, tenantId, match.orderId(), orderStatus));
        actions.after(ActionStep.ORDER_STATUS, c);
        expectOne(matches.setPaymentStatusWithin(c, tenantId, pay.id(), pay.hasTransactionId() ? "RECEIVED" : "UNVERIFIED"));
        actions.after(ActionStep.PAYMENT_STATUS, c);
        AuditLogRepository.appendWithin(c, tenantId, actor, "MATCH_REVERSED", matchId.toString());
        actions.after(ActionStep.AUDIT, c);
        return Decision.done(matchId, orderStatus);
    }

    /**
     * The seller says a received payment belongs to no order: its status becomes {@code UNMATCHED} and every open suggestion for it is
     * rejected, by them (so none is proposed again). Only the seller sets this status; the engine never does. Only a {@code RECEIVED}
     * payment can be set aside.
     */
    public Decision markUnmatched(UUID tenantId, UUID paymentId, UUID actor) {
        return movePayment(tenantId, paymentId, actor, "RECEIVED", "UNMATCHED", "PAYMENT_UNMATCHED");
    }

    /** The seller takes a payment back from {@code UNMATCHED} to {@code RECEIVED}, so that it can be matched again. */
    public Decision reopen(UUID tenantId, UUID paymentId, UUID actor) {
        return movePayment(tenantId, paymentId, actor, "UNMATCHED", "RECEIVED", "PAYMENT_REOPENED");
    }

    private Decision movePayment(UUID tenantId, UUID paymentId, UUID actor, String from, String to, String auditAction) {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(actor, "actor");
        Decision decision = decide(tenantId, c -> {
            if (!matches.isSellerWithin(c, tenantId, actor)) {
                return Decision.refused(Reason.NOT_A_SELLER);
            }
            Optional<MatchRepository.LockedPayment> payment = matches.lockPayment(c, tenantId, paymentId);
            if (payment.isEmpty()) {
                return Decision.refused(Reason.NOT_FOUND);
            }
            actions.after(ActionStep.LOCKED, c);
            if (!from.equals(payment.get().status())) {
                return Decision.refused(Reason.PAYMENT_STATUS_NOT_ALLOWED);
            }
            expectOne(matches.setPaymentStatusWithin(c, tenantId, paymentId, to));
            actions.after(ActionStep.PAYMENT_STATUS, c);
            if ("UNMATCHED".equals(to)) {
                matches.rejectOpenForPaymentWithin(c, tenantId, paymentId, actor, clock.instant());
                actions.after(ActionStep.SUGGESTIONS_REJECTED, c);
            }
            AuditLogRepository.appendWithin(c, tenantId, actor, auditAction, paymentId.toString());
            actions.after(ActionStep.AUDIT, c);
            return Decision.done(paymentId, null);
        });
        if (decision.done()) {
            events.paymentMoved(tenantId, paymentId, to);
        } else {
            events.refused(tenantId, auditAction, paymentId, decision.reason());
        }
        return decision;
    }

    private Decision decide(UUID tenantId, TenantScope.SqlWork<Decision> work) {
        try {
            return scope.call(tenantId, work);
        } catch (SQLException | LedgerException e) {
            throw new IllegalStateException("Could not complete a seller's action", e);
        }
    }

    /**
     * The seller asks the engine to look again at the tenant's open payments (the most recent {@code candidateCap}), for the case where
     * a trigger was missed. Each payment is its own run, exactly as when it arrives: it can be matched automatically if everything
     * holds, suggested, or left alone; a pair the seller rejected or reversed is never touched. A failure on one payment is logged and
     * does not stop the others. A pass can only add matches and suggestions, never undo one; because a match made late in a pass can
     * clear a rival for an earlier payment, a second pass may add more, and the passes stop changing things after at most as many passes as
     * there are free payments (a property test checks this).
     */
    public RematchResult rematch(UUID tenantId, UUID actor) {
        Objects.requireNonNull(actor, "actor");
        boolean seller;
        try {
            seller = scope.call(tenantId, c -> matches.isSellerWithin(c, tenantId, actor));
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check the user", e);
        }
        if (!seller) {
            events.refused(tenantId, "REMATCH", tenantId, Reason.NOT_A_SELLER);
            return new RematchResult(Reason.NOT_A_SELLER, 0, 0, 0);
        }
        AuditLogRepository audit = new AuditLogRepository(dataSource);
        audit.append(tenantId, actor, "REMATCH", tenantId.toString());
        int examined = 0;
        int applied = 0;
        int suggested = 0;
        for (MatchRepository.PaymentCandidate candidate : matches.openPaymentCandidates(tenantId, null, config.candidateCap())) {
            examined++;
            try {
                Run run = onPaymentRecorded(tenantId, candidate.facts().id());
                if (run.kind() == RunKind.APPLIED) {
                    applied++;
                }
                suggested += run.suggestions();
            } catch (RuntimeException e) {
                events.failed(tenantId, "payment", candidate.facts().id());
            }
        }
        events.rematched(tenantId, examined, applied, suggested);
        return new RematchResult(null, examined, applied, suggested);
    }

    /** A write that changed no row, or several, means the locked state was not what we believed: stop and roll back. */
    private static void expectOne(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("a write changed " + rows + " rows instead of one");
        }
    }
}
