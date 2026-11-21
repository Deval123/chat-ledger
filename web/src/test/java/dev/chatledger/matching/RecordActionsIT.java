package dev.chatledger.matching;

import dev.chatledger.orders.IngestionResult;
import dev.chatledger.orders.OrderApprovalService;
import dev.chatledger.orders.RecordActionsService;
import dev.chatledger.orders.RecordActionsService.Outcome;
import dev.chatledger.orders.RecordActionsService.Step;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.count;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seller cancels an order or dismisses a record (task 12b, first pull request): each is one transaction that reverses what has to be
 * reversed, audits who, refuses everything else without changing anything, and leaves nothing behind when it fails at any step.
 */
class RecordActionsIT {

    private final MatchingFixture f = new MatchingFixture();
    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final UUID seller = MatchingFixture.user(tenant, "SELLER");
    private final UUID helper = MatchingFixture.user(tenant, "HELPER");
    private final RecordActionsService actions = service(RecordActionsService.Hook.NONE);

    private RecordActionsService service(RecordActionsService.Hook hook) {
        return new RecordActionsService(MatchingFixture.POOL, f.repository, f.orderRepository, f.clock, hook);
    }

    /** A payable order recorded as a seller records it (so it has its ORDER_ACCEPTED posting), with the engine off. */
    private UUID payableOrder(String ref, long total) {
        MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
        IngestionResult r = quiet.orders.ingest(tenant, seller, id(), MatchingFixture.orderText(ref, "Ana Kamga", PHONE, total));
        assertThat(r.outcome()).isEqualTo(IngestionResult.Outcome.CREATED);
        return r.orderId();
    }

    private UUID orderNeedingReview() {
        IngestionResult r = f.orders.ingest(tenant, seller, id(), "Order: R-" + id() + "\nName: Ana Kamga\nPhone: " + PHONE + "\n2 x rice\n1 x mystery");
        assertThat(r.outcome()).isEqualTo(IngestionResult.Outcome.CREATED_NEEDS_REVIEW);
        return r.orderId();
    }

    private String fingerprint() {
        return MatchInvariants.fingerprint(tenant) + "\ndismissals=" + count("SELECT count(*) FROM record_dismissal WHERE tenant_id = ?", tenant, null);
    }

    // ── Cancel ──────────────────────────────────────────────────────────────

    @Test
    void a_cancel_reverses_the_acceptance_rejects_the_suggestions_on_the_sellers_behalf_and_audits_who() {
        UUID order = payableOrder("C-1", 5000);
        UUID payment = w.payment(4000, "RECEIVED");
        UUID suggestion = w.suggested(order, payment);
        assertThat(actions.cancel(tenant, order, seller)).isEqualTo(Outcome.DONE);

        assertThat(MatchingFixture.status("orders", order)).isEqualTo("CANCELLED");
        assertThat(MatchingFixture.ledger(tenant)).containsExactly(
                "ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000", "REVERSAL 1100 C5000", "REVERSAL 4000 D5000");
        assertThat(count("SELECT count(*) FROM ledger_txn r JOIN ledger_txn t ON t.id = r.reverses_txn_id WHERE r.tenant_id = ? "
                + "AND t.kind = 'ORDER_ACCEPTED' AND t.ref_id = '" + order + "'", tenant, null)).as("the order's own posting is the one reversed").isEqualTo(1);
        assertThat(MatchingFixture.rows("SELECT state || ' ' || decided_by FROM match WHERE tenant_id = ? AND id = '" + suggestion + "'", tenant))
                .containsExactly("REJECTED " + seller);
        assertThat(MatchingFixture.rows("SELECT actor || ' ' || subject FROM audit_log WHERE tenant_id = ? AND action = 'ORDER_CANCELLED'", tenant))
                .containsExactly(seller + " " + order);
        assertThat(MatchingFixture.status("payment", payment)).as("the payment is untouched").isEqualTo("RECEIVED");
        assertThat(MatchingFixture.trialBalance(tenant)).isZero();
        MatchInvariants.assertHoldStrict(tenant);
    }

    @Test
    void a_cancelled_order_is_never_matched_again_by_the_engine_or_by_hand() {
        UUID order = payableOrder("C-2", 5000);
        assertThat(actions.cancel(tenant, order, seller)).isEqualTo(Outcome.DONE);
        UUID payment = f.payments.ingest(tenant, seller, id(), f.sms(5000, "Ana Kamga", PHONE, "C-2")).paymentId();
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("RECEIVED");
        assertThat(count("SELECT count(*) FROM match WHERE tenant_id = ? AND order_id = '" + order + "'", tenant, null)).isZero();
        MatchingService.ApplyResult manual = f.matcher.matchManually(tenant, order, payment, seller);
        assertThat(manual.reason()).isEqualTo(MatchingService.Reason.ORDER_NOT_OPEN);
        MatchInvariants.assertHoldStrict(tenant);
    }

    @Test
    void a_second_cancel_is_refused_and_changes_nothing() {
        UUID order = payableOrder("C-3", 5000);
        assertThat(actions.cancel(tenant, order, seller)).isEqualTo(Outcome.DONE);
        String after = fingerprint();
        assertThat(actions.cancel(tenant, order, seller)).isEqualTo(Outcome.ALREADY_CANCELLED);
        assertThat(fingerprint()).isEqualTo(after);
    }

    @Test
    void a_cancel_is_refused_for_anyone_but_an_enabled_seller_of_the_tenant() {
        UUID order = payableOrder("C-4", 5000);
        MatchWorld other = new MatchWorld();
        UUID sellerOfOther = MatchingFixture.user(other.tenant, "SELLER");
        UUID disabled = MatchingFixture.user(tenant, "SELLER");
        w.run("UPDATE app_user SET disabled_at = now() WHERE id = ?", disabled);
        String before = fingerprint();
        for (UUID who : new UUID[]{helper, disabled, sellerOfOther, UUID.randomUUID()}) {
            assertThat(actions.cancel(tenant, order, who)).isEqualTo(Outcome.NOT_A_SELLER);
            assertThat(actions.dismissOrder(tenant, order, who)).isEqualTo(Outcome.NOT_A_SELLER);
        }
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test
    void a_cancel_is_refused_for_an_unknown_order_and_another_tenants_order() {
        MatchWorld other = new MatchWorld();
        UUID theirs = other.order(5000, "PENDING_PAYMENT", null, null);
        String before = fingerprint();
        assertThat(actions.cancel(tenant, UUID.randomUUID(), seller)).isEqualTo(Outcome.NOT_FOUND);
        assertThat(actions.cancel(tenant, theirs, seller)).isEqualTo(Outcome.NOT_FOUND);
        assertThat(actions.dismissOrder(tenant, theirs, seller)).isEqualTo(Outcome.NOT_FOUND);
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(MatchingFixture.status("orders", theirs)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void a_cancel_is_refused_for_an_order_with_a_payment_applied_and_one_that_was_never_payable() {
        UUID partly = payableOrder("C-5", 5000);
        assertThat(f.matcher.matchManually(tenant, partly, w.payment(2000, "RECEIVED"), seller).outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        UUID paid = payableOrder("C-6", 3000);
        assertThat(f.matcher.matchManually(tenant, paid, w.payment(3000, "RECEIVED"), seller).outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        UUID review = orderNeedingReview();
        UUID unparsed = w.orderWithoutTotal("UNPARSED");
        String before = fingerprint();
        assertThat(actions.cancel(tenant, partly, seller)).isEqualTo(Outcome.HAS_PAYMENT);
        assertThat(actions.cancel(tenant, paid, seller)).isEqualTo(Outcome.HAS_PAYMENT);
        assertThat(actions.cancel(tenant, review, seller)).isEqualTo(Outcome.NOT_PAYABLE);
        assertThat(actions.cancel(tenant, unparsed, seller)).isEqualTo(Outcome.NOT_PAYABLE);
        assertThat(fingerprint()).isEqualTo(before);
        MatchInvariants.assertHoldStrict(tenant);
    }

    @Test
    void after_an_unmatch_the_order_can_be_cancelled() {
        UUID order = payableOrder("C-7", 5000);
        MatchingService.ApplyResult applied = f.matcher.matchManually(tenant, order, w.payment(5000, "RECEIVED"), seller);
        assertThat(actions.cancel(tenant, order, seller)).isEqualTo(Outcome.HAS_PAYMENT);
        assertThat(f.matcher.unmatch(tenant, applied.matchId(), seller).done()).isTrue();
        assertThat(actions.cancel(tenant, order, seller)).isEqualTo(Outcome.DONE);
        assertThat(MatchingFixture.trialBalance(tenant)).isZero();
        MatchInvariants.assertHoldStrict(tenant);
    }

    @Test
    void a_cancel_that_fails_after_any_step_leaves_nothing_behind() {
        for (Step step : new Step[]{Step.LOCKED, Step.ACCEPTANCE_REVERSED, Step.STATUS_SET, Step.SUGGESTIONS_REJECTED, Step.AUDIT}) {
            UUID order = payableOrder("CF-" + step.ordinal(), 5000);
            w.suggested(order, w.payment(4000, "RECEIVED"));
            String before = fingerprint();
            RecordActionsService failing = service((s, c) -> {
                if (s == step) {
                    throw new java.sql.SQLException("fails after " + s);
                }
            });
            assertThatThrownBy(() -> failing.cancel(tenant, order, seller)).as(step.name()).isInstanceOf(IllegalStateException.class);
            assertThat(fingerprint()).as("nothing left after " + step).isEqualTo(before);
            assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
        }
        MatchInvariants.assertHoldStrict(tenant);
    }

    // ── Dismiss ─────────────────────────────────────────────────────────────

    @Test
    void a_record_that_never_reached_the_books_is_dismissed_once_with_who_and_when() {
        UUID unparsedOrder = w.orderWithoutTotal("UNPARSED");
        UUID review = orderNeedingReview();
        UUID unparsedPayment = w.unparsedPayment();
        String ledger = String.valueOf(MatchingFixture.ledger(tenant));
        assertThat(actions.dismissOrder(tenant, unparsedOrder, seller)).isEqualTo(Outcome.DONE);
        assertThat(actions.dismissOrder(tenant, review, seller)).isEqualTo(Outcome.DONE);
        assertThat(actions.dismissPayment(tenant, unparsedPayment, seller)).isEqualTo(Outcome.DONE);
        assertThat(String.valueOf(MatchingFixture.ledger(tenant))).as("nothing is posted").isEqualTo(ledger);
        assertThat(count("SELECT count(*) FROM record_dismissal WHERE tenant_id = ? AND dismissed_by = '" + seller + "'", tenant, null)).isEqualTo(3);
        assertThat(MatchingFixture.rows("SELECT action || ' ' || subject FROM audit_log WHERE tenant_id = ? AND action LIKE '%_DISMISSED' ORDER BY 1", tenant))
                .containsExactlyInAnyOrder("ORDER_DISMISSED " + unparsedOrder, "ORDER_DISMISSED " + review, "PAYMENT_DISMISSED " + unparsedPayment);
        String after = fingerprint();
        assertThat(actions.dismissOrder(tenant, review, seller)).isEqualTo(Outcome.ALREADY_DISMISSED);
        assertThat(actions.dismissPayment(tenant, unparsedPayment, seller)).isEqualTo(Outcome.ALREADY_DISMISSED);
        assertThat(fingerprint()).isEqualTo(after);
    }

    @Test
    void a_dismissed_order_cannot_be_made_payable() {
        UUID review = orderNeedingReview();
        assertThat(actions.dismissOrder(tenant, review, seller)).isEqualTo(Outcome.DONE);
        String after = fingerprint();
        OrderApprovalService approvals = new OrderApprovalService(MatchingFixture.POOL, f.repository, f.orderRepository, f.matcher, f.clock);
        assertThat(approvals.approve(tenant, review, seller, 5000)).isEqualTo(OrderApprovalService.Outcome.NOT_REVIEWABLE);
        assertThat(fingerprint()).isEqualTo(after);
        assertThat(MatchingFixture.status("orders", review)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void a_record_in_the_books_or_cancelled_cannot_be_dismissed() {
        UUID payable = payableOrder("D-1", 5000);
        UUID cancelled = payableOrder("D-2", 5000);
        assertThat(actions.cancel(tenant, cancelled, seller)).isEqualTo(Outcome.DONE);
        UUID received = w.payment(4000, "RECEIVED");
        UUID unverified = w.payment(4000, "UNVERIFIED", false);
        String before = fingerprint();
        assertThat(actions.dismissOrder(tenant, payable, seller)).isEqualTo(Outcome.NOT_DISMISSABLE);
        assertThat(actions.dismissOrder(tenant, cancelled, seller)).isEqualTo(Outcome.NOT_DISMISSABLE);
        assertThat(actions.dismissPayment(tenant, received, seller)).isEqualTo(Outcome.NOT_DISMISSABLE);
        assertThat(actions.dismissPayment(tenant, unverified, seller)).isEqualTo(Outcome.NOT_DISMISSABLE);
        assertThat(actions.dismissPayment(tenant, UUID.randomUUID(), seller)).isEqualTo(Outcome.NOT_FOUND);
        assertThat(actions.dismissPayment(tenant, received, helper)).isEqualTo(Outcome.NOT_A_SELLER);
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test
    void a_dismissal_that_fails_after_any_step_leaves_nothing_behind() {
        for (Step step : new Step[]{Step.LOCKED, Step.DISMISSED, Step.AUDIT}) {
            UUID order = w.orderWithoutTotal("UNPARSED");
            UUID payment = w.unparsedPayment();
            String before = fingerprint();
            RecordActionsService failing = service((s, c) -> {
                if (s == step) {
                    throw new java.sql.SQLException("fails after " + s);
                }
            });
            assertThatThrownBy(() -> failing.dismissOrder(tenant, order, seller)).as(step.name()).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> failing.dismissPayment(tenant, payment, seller)).as(step.name()).isInstanceOf(IllegalStateException.class);
            assertThat(fingerprint()).as("nothing left after " + step).isEqualTo(before);
        }
    }
}
