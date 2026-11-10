package dev.chatledger.matching;

import dev.chatledger.suspicion.FlagCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static dev.chatledger.matching.BoardRepositoryIsolationIT.flagOrder;
import static dev.chatledger.matching.BoardRepositoryIsolationIT.flagPayment;
import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.count;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 7, D3 and D12: a flagged pair is never matched automatically; it is suggested instead, and the seller confirms. One test per flag code
 * (each on a pair that a control shows would otherwise be matched automatically), the scope of a flag (payment, order, pair), resolved flags,
 * the pair rules found under the lock, and the finding and the suggestion committing together.
 *
 * <p>Requirements: 5, 3.2. Task 7.
 */
class FlagMatchingIT {
    private final MatchingFixture f = new MatchingFixture();
    private final MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final UUID seller = MatchingFixture.user(tenant, "SELLER");

    private UUID order(String ref, String name, String phone, long total) {
        return quiet.orders.ingest(tenant, seller, id(), MatchingFixture.orderText(ref, name, phone, total)).orderId();
    }

    private UUID payment(String text) {
        return quiet.payments.ingest(tenant, seller, id(), text).paymentId();
    }

    /** A payment that pays the order exactly, by reference and phone, recorded by the seller: automatic unless flagged. */
    private UUID exact(String ref) {
        return payment(quiet.sms(5000, "Ana Kamga", PHONE, ref));
    }

    private long auto() {
        return count("SELECT count(*) FROM match WHERE tenant_id = ? AND state = 'AUTO'", tenant, null);
    }

    private long suggested(UUID order, UUID payment) {
        return count("SELECT count(*) FROM match WHERE tenant_id = ? AND state = 'SUGGESTED' AND order_id = '" + order + "' AND payment_id = ?", tenant,
                payment);
    }

    private long flags(String code, UUID payment, UUID order) {
        return count("SELECT count(*) FROM flag WHERE tenant_id = ? AND code = '" + code + "' AND " + (payment == null ? "payment_id IS NULL" : "payment_id = '" + payment + "'")
                + " AND " + (order == null ? "order_id IS NULL" : "order_id = '" + order + "'"), tenant, null);
    }

    private static boolean isPairCode(FlagCode code) {
        return List.of(FlagCode.AMOUNT_NEAR_MISS, FlagCode.PAID_BEFORE_ORDER, FlagCode.PHONE_MISMATCH, FlagCode.NAME_MISMATCH, FlagCode.OVERPAYMENT)
                .contains(code);
    }

    @Test
    void control_the_same_pair_without_a_flag_is_matched_automatically() {
        UUID order = order("A-1", "Ana Kamga", PHONE, 5000);
        UUID payment = exact("A-1");
        assertThat(count("SELECT count(*) FROM flag WHERE tenant_id = ?", tenant, null)).as("no rule fires on this pair").isZero();
        assertThat(f.matcher.onPaymentRecorded(tenant, payment).kind()).isEqualTo(MatchingService.RunKind.APPLIED);
        assertThat(auto()).isEqualTo(1);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
    }

    /** One test per code: a flag of that code, in its shape, on a pair that would otherwise be automatic. */
    @ParameterizedTest
    @EnumSource(FlagCode.class)
    void a_flag_of_each_code_makes_the_pair_a_suggestion(FlagCode code) {
        UUID order = order("A-1", "Ana Kamga", PHONE, 5000);
        UUID payment = exact("A-1");
        if (isPairCode(code)) {
            flagPayment(w, payment, code.name(), order, false);
        } else {
            flagPayment(w, payment, code.name(), null, false);
        }
        MatchingService.Run run = f.matcher.onPaymentRecorded(tenant, payment);
        assertThat(run.kind()).as(code.name()).isEqualTo(MatchingService.RunKind.SUGGESTED);
        assertThat(auto()).isZero();
        assertThat(suggested(order, payment)).isEqualTo(1);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
        assertThat(MatchingFixture.ledger(tenant)).as("nothing posted for the payment").containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000");
        // Called directly, the automatic step refuses too, under the lock.
        assertThat(f.matcher.apply(tenant, order, payment, MatchingService.Mode.AUTO, null).reason()).isEqualTo(MatchingService.Reason.FLAGGED);
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void an_order_flag_of_its_own_makes_every_pair_of_that_order_a_suggestion() {
        UUID order = order("A-1", "Ana Kamga", PHONE, 5000);
        flagOrder(w, order, false);
        UUID payment = exact("A-1");
        assertThat(f.matcher.onPaymentRecorded(tenant, payment).kind()).isEqualTo(MatchingService.RunKind.SUGGESTED);
        assertThat(auto()).isZero();
        assertThat(f.matcher.apply(tenant, order, payment, MatchingService.Mode.AUTO, null).reason()).isEqualTo(MatchingService.Reason.FLAGGED);
    }

    // ── Scope (owner's clarification 1) ────────────────────────────────────

    /** Two open orders of one customer; the payment names B, so B is the clear automatic match and A a weaker candidate. */
    private record TwoOrders(UUID a, UUID b, UUID payment) {
    }

    private TwoOrders twoOrders() {
        UUID a = order("A-1", "Ana Kamga", PHONE, 5000);
        UUID b = order("B-1", "Ana Kamga", PHONE, 5000);
        return new TwoOrders(a, b, exact("B-1"));
    }

    @Test
    void a_pair_flag_on_p_and_a_does_not_block_p_and_b() {
        TwoOrders t = twoOrders();
        flagPayment(w, t.payment, "PHONE_MISMATCH", t.a, false);
        assertThat(f.matcher.onPaymentRecorded(tenant, t.payment).kind()).isEqualTo(MatchingService.RunKind.APPLIED);
        assertThat(count("SELECT count(*) FROM match WHERE tenant_id = ? AND state = 'AUTO' AND order_id = ?", tenant, t.b)).isEqualTo(1);
        assertThat(MatchingFixture.status("orders", t.a)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void a_pair_flag_on_p_and_a_does_not_block_another_payment_q_on_a() {
        UUID a = order("A-1", "Ana Kamga", PHONE, 5000);
        UUID p = payment(quiet.sms(3000, "Ana Kamga", PHONE, "Z-9"));
        flagPayment(w, p, "PHONE_MISMATCH", a, false);
        UUID q = exact("A-1");
        assertThat(f.matcher.onPaymentRecorded(tenant, q).kind()).as("(P, A)'s flag is neither A's nor Q's").isEqualTo(MatchingService.RunKind.APPLIED);
        assertThat(count("SELECT count(*) FROM match WHERE tenant_id = ? AND state = 'AUTO' AND payment_id = ?", tenant, q)).isEqualTo(1);
        assertThat(MatchingFixture.status("orders", a)).isEqualTo("PAID");
    }

    @Test
    void a_pair_flag_on_p_and_b_blocks_p_and_b() {
        TwoOrders t = twoOrders();
        flagPayment(w, t.payment, "PHONE_MISMATCH", t.b, false);
        assertThat(f.matcher.apply(tenant, t.b, t.payment, MatchingService.Mode.AUTO, null).reason()).isEqualTo(MatchingService.Reason.FLAGGED);
        assertThat(auto()).isZero();
    }

    @Test
    void a_payment_only_flag_blocks_p_and_b() {
        TwoOrders t = twoOrders();
        flagPayment(w, t.payment, "SENDER_BURST", null, false);
        assertThat(f.matcher.onPaymentRecorded(tenant, t.payment).kind()).isEqualTo(MatchingService.RunKind.SUGGESTED);
        assertThat(auto()).isZero();
        assertThat(f.matcher.apply(tenant, t.b, t.payment, MatchingService.Mode.AUTO, null).reason()).isEqualTo(MatchingService.Reason.FLAGGED);
        assertThat(f.matcher.apply(tenant, t.a, t.payment, MatchingService.Mode.AUTO, null).reason()).isEqualTo(MatchingService.Reason.FLAGGED);
    }

    @Test
    void an_order_flag_on_b_blocks_p_and_b_and_an_order_flag_on_a_does_not() {
        TwoOrders t = twoOrders();
        flagOrder(w, t.a, false);
        assertThat(f.matcher.onPaymentRecorded(tenant, t.payment).kind()).as("A's flag is not B's").isEqualTo(MatchingService.RunKind.APPLIED);
        FlagMatchingIT other = new FlagMatchingIT();   // a second workspace, so the pair (P, B) is still free there
        TwoOrders u = other.twoOrders();
        flagOrder(other.w(), u.b, false);
        assertThat(f.matcher.apply(other.tenant(), u.b, u.payment, MatchingService.Mode.AUTO, null).reason()).isEqualTo(MatchingService.Reason.FLAGGED);
        assertThat(f.matcher.onPaymentRecorded(other.tenant(), u.payment).kind()).isEqualTo(MatchingService.RunKind.SUGGESTED);
    }

    @Test
    void a_resolved_flag_still_blocks_the_engine_and_the_seller_can_still_confirm() {
        UUID order = order("A-1", "Ana Kamga", PHONE, 5000);
        UUID payment = exact("A-1");
        flagPayment(w, payment, "SENDER_BURST", null, true);
        assertThat(f.matcher.onPaymentRecorded(tenant, payment).kind()).as("resolved is not unflagged (D3)").isEqualTo(MatchingService.RunKind.SUGGESTED);
        UUID suggestion = UUID.fromString(MatchingFixture.rows("SELECT id::text FROM match WHERE tenant_id = ? AND state = 'SUGGESTED'", tenant).get(0));
        MatchingService.ApplyResult confirmed = f.matcher.confirm(tenant, suggestion, seller);
        assertThat(confirmed.outcome()).as("flags are advisory: the seller decides (D8)").isEqualTo(MatchingService.Outcome.APPLIED);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void an_open_flag_never_refuses_the_sellers_manual_match() {
        UUID order = order("A-1", "Ana Kamga", PHONE, 5000);
        UUID payment = exact("A-1");
        flagPayment(w, payment, "SENDER_BURST", null, false);
        assertThat(f.matcher.matchManually(tenant, order, payment, seller).outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
    }

    // ── The pair rules under the lock, and what commits together (owner's clarification 2) ──

    private String smsPaidAgo(long amount, String name, String phone, String ref, Duration ago) {
        return "You received " + amount + " XAF from " + name + " (" + phone + "). Txn " + MatchingFixture.tx() + ". Ref " + ref + ". " + quiet.date(ago);
    }

    @Test
    void a_payment_made_hours_before_its_order_is_found_under_the_lock_and_the_finding_and_the_suggestion_commit_together() {
        UUID payment = payment(smsPaidAgo(5000, "Ana Kamga", PHONE, "A-1", Duration.ofHours(2)));
        UUID order = order("A-1", "Ana Kamga", PHONE, 5000);
        assertThat(count("SELECT count(*) FROM flag WHERE tenant_id = ?", tenant, null)).as("nothing found before the matcher runs").isZero();
        MatchingService.ApplyResult r = f.matcher.apply(tenant, order, payment, MatchingService.Mode.AUTO, null);
        assertThat(r.outcome()).isEqualTo(MatchingService.Outcome.DEGRADED_TO_SUGGESTION);
        assertThat(r.reason()).isEqualTo(MatchingService.Reason.FLAGGED);
        assertThat(r.matchId()).as("the suggestion written with the finding").isNotNull();
        assertThat(r.suggestionWritten()).as("this step wrote it").isTrue();
        // Both are there after the degrade: the finding and the suggestion committed in one transaction.
        assertThat(flags("PAID_BEFORE_ORDER", payment, order)).isEqualTo(1);
        assertThat(suggested(order, payment)).isEqualTo(1);
        assertThat(f.repository.find(tenant, r.matchId()).orElseThrow().state()).isEqualTo("SUGGESTED");
        assertThat(auto()).isZero();
    }

    @Test
    void a_flagged_run_counts_the_suggestion_it_wrote_once_and_never_again() {
        UUID payment = payment(smsPaidAgo(5000, "Ana Kamga", PHONE, "A-1", Duration.ofHours(2)));
        UUID order = order("A-1", "Ana Kamga", PHONE, 5000);
        MatchingService.Run first = f.matcher.onPaymentRecorded(tenant, payment);
        assertThat(first.kind()).isEqualTo(MatchingService.RunKind.SUGGESTED);
        assertThat(first.suggestions()).as("the suggestion this run wrote, with its finding").isEqualTo(1);
        MatchingService.Run second = f.matcher.onPaymentRecorded(tenant, payment);
        assertThat(second.kind()).isEqualTo(MatchingService.RunKind.SUGGESTED);
        assertThat(second.suggestions()).as("the same suggestion, kept: nothing new").isZero();
        assertThat(suggested(order, payment)).isEqualTo(1);
        assertThat(auto()).isZero();
    }

    @Test
    void a_failure_after_the_flagged_step_leaves_neither_the_finding_nor_the_suggestion() {
        UUID payment = payment(smsPaidAgo(5000, "Ana Kamga", PHONE, "A-1", Duration.ofHours(2)));
        UUID order = order("A-1", "Ana Kamga", PHONE, 5000);
        MatchingFixture failing = new MatchingFixture(MatchConfig.defaults(), (step, c) -> {
            if (step == MatchingService.ApplyStep.FLAGGED) {
                throw new IllegalStateException("injected failure after " + step);
            }
        }, false);
        assertThatThrownBy(() -> failing.matcher.apply(tenant, order, payment, MatchingService.Mode.AUTO, null)).hasMessageContaining("injected failure");
        assertThat(count("SELECT count(*) FROM flag WHERE tenant_id = ?", tenant, null)).as("no finding without its suggestion").isZero();
        assertThat(count("SELECT count(*) FROM match WHERE tenant_id = ?", tenant, null)).as("no suggestion without its finding").isZero();
        // and the retry writes both, once: the first retry reports the suggestion it wrote, the second keeps it and reports none
        MatchingService.ApplyResult first = f.matcher.apply(tenant, order, payment, MatchingService.Mode.AUTO, null);
        MatchingService.ApplyResult second = f.matcher.apply(tenant, order, payment, MatchingService.Mode.AUTO, null);
        assertThat(first.reason()).isEqualTo(MatchingService.Reason.FLAGGED);
        assertThat(second.reason()).isEqualTo(MatchingService.Reason.FLAGGED);
        assertThat(first.suggestionWritten()).isTrue();
        assertThat(second.suggestionWritten()).as("the suggestion already there is kept, not written again").isFalse();
        assertThat(second.matchId()).isEqualTo(first.matchId());
        assertThat(flags("PAID_BEFORE_ORDER", payment, order)).isEqualTo(1);
        assertThat(suggested(order, payment)).isEqualTo(1);
    }

    @Test
    void a_payment_from_another_phone_under_the_customers_name_is_found_under_the_lock() {
        UUID order = order("A-1", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(quiet.sms(5000, "Ana Kamga", "+999 000 000 5678", "A-1"));
        assertThat(f.matcher.onPaymentRecorded(tenant, payment).kind()).isEqualTo(MatchingService.RunKind.SUGGESTED);
        assertThat(flags("PHONE_MISMATCH", payment, order)).isEqualTo(1);
        assertThat(suggested(order, payment)).isEqualTo(1);
        assertThat(auto()).isZero();
    }

    @Test
    void the_pair_rules_run_on_suggestions_too_so_the_seller_sees_the_flags_with_them() {
        UUID nearMiss = order("N-1", "Ana Kamga", PHONE, 50000);
        UUID nearPayment = payment(quiet.sms(5000, "Ana Kamga", PHONE, "N-1"));
        f.matcher.onPaymentRecorded(tenant, nearPayment);
        assertThat(suggested(nearMiss, nearPayment)).isEqualTo(1);
        assertThat(flags("AMOUNT_NEAR_MISS", nearPayment, nearMiss)).isEqualTo(1);

        UUID over = order("O-1", "Bea Nkomo", "+999 000 000 2222", 5000);
        UUID overPayment = payment(quiet.sms(5600, "Bea Nkomo", "+999 000 000 2222", "O-1"));
        f.matcher.onPaymentRecorded(tenant, overPayment);
        assertThat(suggested(over, overPayment)).isEqualTo(1);
        assertThat(flags("OVERPAYMENT", overPayment, over)).isEqualTo(1);

        UUID stranger = order("S-1", "Cy Mbarga", "+999 000 000 3333", 7000);
        UUID strangerPayment = payment(quiet.sms(7000, "Dee Synthetic", "+999 000 000 4444", "S-1"));
        f.matcher.onPaymentRecorded(tenant, strangerPayment);
        assertThat(suggested(stranger, strangerPayment)).isEqualTo(1);
        assertThat(flags("NAME_MISMATCH", strangerPayment, stranger)).isEqualTo(1);
        assertThat(flags("PHONE_MISMATCH", strangerPayment, stranger)).isEqualTo(1);
        assertThat(auto()).isZero();
        MatchInvariants.assertHold(tenant);
    }

    UUID tenant() {
        return tenant;
    }

    MatchWorld w() {
        return w;
    }
}
