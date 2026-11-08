package dev.chatledger.matching;

import dev.chatledger.orders.LogCapture;
import dev.chatledger.payments.PaymentIngestionResult;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.count;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The seller's decisions on suggestions and pairs: confirm, reject, match by hand. For each: what it does, who and when it records,
 * and everything it refuses (a user who is not an enabled seller of the tenant, another tenant's ids, a match that is not what the
 * action needs, a payment or order that cannot be matched), with the proof that a refusal changes nothing at all. All names and
 * numbers are synthetic.
 *
 * <p>Requirements: 3.3, 3.4, 3.7, 10. Task 6.3.
 */
class SellerDecisionsIT {

    private final MatchingFixture f = new MatchingFixture();
    private final MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final UUID seller = MatchingFixture.user(tenant, "SELLER");
    private final UUID helper = MatchingFixture.user(tenant, "HELPER");

    private UUID order(String ref, String name, String phone, long total) {
        return quiet.orders.ingest(tenant, seller, id(), MatchingFixture.orderText(ref, name, phone, total)).orderId();
    }

    private UUID payment(UUID actor, String text) {
        PaymentIngestionResult r = quiet.payments.ingest(tenant, actor, id(), text);
        assertThat(r.stored()).isTrue();
        return r.paymentId();
    }

    /** A perfect pair, recorded by a helper, so that the engine proposes it and does not apply it. Returns the suggestion's id. */
    private UUID suggestion(UUID order, UUID payment) {
        f.matcher.onPaymentRecorded(tenant, payment);
        return f.repository.forPayment(tenant, payment).stream().filter(m -> m.orderId().equals(order) && m.state().equals("SUGGESTED"))
                .findFirst().orElseThrow().id();
    }

    private UUID perfectSuggestion() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(helper, quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        return suggestion(order, payment);
    }

    private long audit(String action, UUID actor) {
        return count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = '" + action + "' AND actor = ?", tenant, actor);
    }

    // ── Confirm ────────────────────────────────────────────────────────────

    @Test
    void confirming_a_suggestion_posts_it_and_records_who_and_when() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(helper, quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        UUID suggestion = suggestion(order, payment);
        f.clock.advance(java.time.Duration.ofMinutes(3));

        MatchingService.ApplyResult r = f.matcher.confirm(tenant, suggestion, seller);

        assertThat(r.outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        assertThat(r.matchId()).as("the suggestion itself becomes the match").isEqualTo(suggestion);
        MatchRepository.Stored m = f.repository.find(tenant, suggestion).orElseThrow();
        assertThat(m.state()).isEqualTo("CONFIRMED");
        assertThat(m.decidedBy()).isEqualTo(seller);
        assertThat(m.decidedAt()).isEqualTo(f.clock.instant());
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("MATCHED");
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000",
                "PAYMENT_MATCHED 1000 D5000", "PAYMENT_MATCHED 1100 C5000");
        assertThat(audit("MATCH_CONFIRMED", seller)).isEqualTo(1);
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void confirming_refuses_everything_that_is_not_an_open_suggestion_for_an_enabled_seller_and_changes_nothing() {
        UUID suggestion = perfectSuggestion();
        MatchWorld other = new MatchWorld();
        UUID sellerOfOther = MatchingFixture.user(other.tenant, "SELLER");
        UUID disabled = MatchingFixture.user(tenant, "SELLER");
        w.run("UPDATE app_user SET disabled_at = now() WHERE id = ?", disabled);
        String before = MatchInvariants.fingerprint(tenant);

        assertThat(f.matcher.confirm(tenant, suggestion, helper).reason()).as("a helper").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.confirm(tenant, suggestion, disabled).reason()).as("a disabled seller").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.confirm(tenant, suggestion, sellerOfOther).reason()).as("a seller of another tenant").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.confirm(tenant, suggestion, UUID.randomUUID()).reason()).as("an unknown user").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.confirm(tenant, UUID.randomUUID(), seller).reason()).as("an unknown match").isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(f.matcher.confirm(other.tenant, suggestion, sellerOfOther).reason()).as("another tenant's match id").isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(MatchInvariants.fingerprint(tenant)).as("nothing changed").isEqualTo(before);
        assertThat(f.repository.find(tenant, suggestion).orElseThrow().state()).isEqualTo("SUGGESTED");
    }

    @Test
    void a_match_that_is_not_a_suggestion_cannot_be_confirmed_and_never_becomes_a_manual_match_by_the_back_door() {
        UUID suggestion = perfectSuggestion();
        assertThat(f.matcher.confirm(tenant, suggestion, seller).outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        String afterFirst = MatchInvariants.fingerprint(tenant);
        assertThat(f.matcher.confirm(tenant, suggestion, seller).reason()).as("confirmed twice").isEqualTo(MatchingService.Reason.PAYMENT_NOT_FREE);
        assertThat(MatchInvariants.fingerprint(tenant)).isEqualTo(afterFirst);

        UUID order = order("B-2", "Bea Nkomo", "+999 000 000 5678", 4000);
        UUID payment = payment(helper, quiet.sms(4000, "Bea Nkomo", "+999 000 000 5678", "B-2"));
        UUID second = suggestion(order, payment);
        assertThat(f.matcher.reject(tenant, second, seller).done()).isTrue();
        String afterReject = MatchInvariants.fingerprint(tenant);
        assertThat(f.matcher.confirm(tenant, second, seller).reason()).as("a rejected suggestion").isEqualTo(MatchingService.Reason.NOT_SUGGESTED);
        assertThat(MatchInvariants.fingerprint(tenant)).as("not turned into a match").isEqualTo(afterReject);
        assertThat(f.repository.forPayment(tenant, payment)).extracting(MatchRepository.Stored::state).containsExactly("REJECTED");

        UUID auto = order("C-3", "Cy Obi", "+999 000 000 7777", 3000);
        UUID autoPayment = quiet.payments.ingest(tenant, seller, id(), quiet.sms(3000, "Cy Obi", "+999 000 000 7777", "C-3")).paymentId();
        f.matcher.onPaymentRecorded(tenant, autoPayment);
        UUID autoMatch = f.repository.forPayment(tenant, autoPayment).get(0).id();
        assertThat(f.repository.find(tenant, autoMatch).orElseThrow().state()).isEqualTo("AUTO");
        String beforeAuto = MatchInvariants.fingerprint(tenant);
        assertThat(f.matcher.confirm(tenant, autoMatch, seller).reason()).as("an automatic match: its payment is already matched").isEqualTo(MatchingService.Reason.PAYMENT_NOT_FREE);
        assertThat(MatchInvariants.fingerprint(tenant)).isEqualTo(beforeAuto);
        assertThat(MatchingFixture.status("orders", auto)).isEqualTo("PAID");
    }

    @Test
    void a_suggestion_for_a_payment_that_was_matched_to_another_order_is_refused_for_that_reason() {
        UUID first = order(null, "Ana Kamga", PHONE, 5000);
        UUID second = order(null, "Ana Kamga", PHONE, 5000);
        UUID payment = payment(helper, quiet.sms(5000, "Ana Kamga", PHONE, null));
        f.matcher.onPaymentRecorded(tenant, payment);
        UUID forFirst = f.repository.forPayment(tenant, payment).stream().filter(m -> m.orderId().equals(first)).findFirst().orElseThrow().id();
        UUID forSecond = f.repository.forPayment(tenant, payment).stream().filter(m -> m.orderId().equals(second)).findFirst().orElseThrow().id();
        assertThat(f.matcher.confirm(tenant, forFirst, seller).outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        String before = MatchInvariants.fingerprint(tenant);
        assertThat(f.matcher.confirm(tenant, forSecond, seller).reason()).isEqualTo(MatchingService.Reason.PAYMENT_NOT_FREE);
        assertThat(MatchInvariants.fingerprint(tenant)).isEqualTo(before);
        assertThat(f.repository.find(tenant, forSecond).orElseThrow().state()).as("retired by the match").isEqualTo("REJECTED");
    }

    @Test
    void confirming_names_the_suggestion_it_confirms_not_just_any_suggestion_for_the_same_pair() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(helper, quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        UUID current = suggestion(order, payment);
        UUID retired = w.rejected(order, payment, false);   // an older suggestion of the same pair, retired by the system
        String before = MatchInvariants.fingerprint(tenant);

        assertThat(f.matcher.confirm(tenant, retired, seller).reason()).isEqualTo(MatchingService.Reason.NOT_SUGGESTED);

        assertThat(MatchInvariants.fingerprint(tenant)).as("the open suggestion was not confirmed in its place").isEqualTo(before);
        assertThat(f.matcher.confirm(tenant, current, seller).matchId()).isEqualTo(current);
    }

    // ── Reject ─────────────────────────────────────────────────────────────

    @Test
    void rejecting_a_suggestion_records_who_and_when_and_the_pair_is_never_proposed_or_applied_again() {
        MatchingFixture off = new MatchingFixture(new MatchConfig(85, 60, false, 200, 10), MatchingService.StepHook.NONE, false);
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(seller, quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        assertThat(off.matcher.onPaymentRecorded(tenant, payment).kind()).as("a perfect pair, but the kill switch is off").isEqualTo(MatchingService.RunKind.SUGGESTED);
        UUID suggestion = f.repository.forPayment(tenant, payment).get(0).id();
        f.clock.advance(java.time.Duration.ofMinutes(2));

        MatchingService.Decision d = f.matcher.reject(tenant, suggestion, seller);

        assertThat(d.done()).isTrue();
        MatchRepository.Stored m = f.repository.find(tenant, suggestion).orElseThrow();
        assertThat(m.state()).isEqualTo("REJECTED");
        assertThat(m.decidedBy()).isEqualTo(seller);
        assertThat(m.decidedAt()).isEqualTo(f.clock.instant());
        assertThat(audit("MATCH_REJECTED", seller)).isEqualTo(1);
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("RECEIVED");
        assertThat(MatchingFixture.ledger(tenant)).as("a rejection posts nothing").hasSize(2);

        // now the switch is on and the pair would be automatic: the seller's no stands
        assertThat(f.matcher.onPaymentRecorded(tenant, payment).kind()).isEqualTo(MatchingService.RunKind.NOTHING);
        assertThat(f.matcher.onOrderRecorded(tenant, order).kind()).isEqualTo(MatchingService.RunKind.NOTHING);
        MatchingService.ApplyResult direct = f.matcher.apply(tenant, order, payment, MatchingService.Mode.AUTO, null);
        assertThat(direct.reason()).as("even when the automatic path is called directly").isEqualTo(MatchingService.Reason.SELLER_DECLINED);
        assertThat(f.matcher.rematch(tenant, seller).applied()).isZero();
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
        // the seller can still change their mind, by hand
        assertThat(f.matcher.matchManually(tenant, order, payment, seller).outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void a_suggestion_retired_by_the_system_is_not_the_sellers_no() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(seller, quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        w.rejected(order, payment, false);   // an older suggestion of this pair, retired by the system: no decider

        MatchingService.ApplyResult r = f.matcher.apply(tenant, order, payment, MatchingService.Mode.AUTO, null);

        assertThat(r.outcome()).as("nobody said no to this pair").isEqualTo(MatchingService.Outcome.APPLIED);
        assertThat(f.matcher.onPaymentRecorded(tenant, payment).kind()).isEqualTo(MatchingService.RunKind.SKIPPED);
    }

    @Test
    void a_rejected_suggestion_is_not_proposed_again_from_the_orders_side_or_from_the_payments_side() {
        UUID suggestion = perfectSuggestion();   // a helper's payment: the engine can only suggest it
        MatchRepository.Stored s = f.repository.find(tenant, suggestion).orElseThrow();
        assertThat(f.matcher.reject(tenant, suggestion, seller).done()).isTrue();

        assertThat(f.matcher.onOrderRecorded(tenant, s.orderId()).kind()).as("from the order").isEqualTo(MatchingService.RunKind.NOTHING);
        assertThat(f.matcher.onPaymentRecorded(tenant, s.paymentId()).kind()).as("from the payment").isEqualTo(MatchingService.RunKind.NOTHING);
        assertThat(f.matcher.rematch(tenant, seller).suggested()).isZero();
        assertThat(f.repository.forPayment(tenant, s.paymentId())).extracting(MatchRepository.Stored::state).containsExactly("REJECTED");
    }

    @Test
    void rejecting_refuses_everything_that_is_not_an_open_suggestion_for_an_enabled_seller_and_changes_nothing() {
        UUID suggestion = perfectSuggestion();
        MatchWorld other = new MatchWorld();
        UUID sellerOfOther = MatchingFixture.user(other.tenant, "SELLER");
        UUID disabled = MatchingFixture.user(tenant, "SELLER");
        w.run("UPDATE app_user SET disabled_at = now() WHERE id = ?", disabled);
        String before = MatchInvariants.fingerprint(tenant);

        assertThat(f.matcher.reject(tenant, suggestion, helper).reason()).isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.reject(tenant, suggestion, disabled).reason()).isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.reject(tenant, suggestion, sellerOfOther).reason()).isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.reject(tenant, UUID.randomUUID(), seller).reason()).isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(f.matcher.reject(other.tenant, suggestion, sellerOfOther).reason()).as("another tenant's match id").isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(MatchInvariants.fingerprint(tenant)).isEqualTo(before);

        assertThat(f.matcher.reject(tenant, suggestion, seller).done()).isTrue();
        String rejected = MatchInvariants.fingerprint(tenant);
        assertThat(f.matcher.reject(tenant, suggestion, seller).reason()).as("twice").isEqualTo(MatchingService.Reason.NOT_SUGGESTED);
        assertThat(MatchInvariants.fingerprint(tenant)).isEqualTo(rejected);

        UUID order = order("B-2", "Bea Nkomo", "+999 000 000 5678", 4000);
        UUID payment = payment(helper, quiet.sms(4000, "Bea Nkomo", "+999 000 000 5678", "B-2"));
        UUID second = suggestion(order, payment);
        f.matcher.confirm(tenant, second, seller);
        assertThat(f.matcher.reject(tenant, second, seller).reason()).as("a confirmed match").isEqualTo(MatchingService.Reason.NOT_SUGGESTED);
        assertThat(f.repository.find(tenant, second).orElseThrow().state()).isEqualTo("CONFIRMED");
    }

    // ── Match by hand ──────────────────────────────────────────────────────

    @Test
    void a_seller_matches_a_pair_the_engine_never_proposed_and_it_is_audited_as_manual() {
        UUID order = order("Z-9", "Zoe Bih", "+999 000 000 4444", 5000);
        UUID payment = payment(seller, quiet.sms(3000, "Someone Else", "+999 000 000 8888", null));
        assertThat(f.matcher.onPaymentRecorded(tenant, payment).kind()).isEqualTo(MatchingService.RunKind.NOTHING);
        f.clock.advance(java.time.Duration.ofMinutes(1));

        MatchingService.ApplyResult r = f.matcher.matchManually(tenant, order, payment, seller);

        assertThat(r.outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        assertThat(r.appliedMinor()).isEqualTo(3000);
        assertThat(r.orderStatus()).isEqualTo("PARTIALLY_PAID");
        MatchRepository.Stored m = f.repository.find(tenant, r.matchId()).orElseThrow();
        assertThat(m.state()).isEqualTo("CONFIRMED");
        assertThat(m.decidedBy()).isEqualTo(seller);
        assertThat(m.decidedAt()).isEqualTo(f.clock.instant());
        assertThat(audit("MATCH_MANUAL", seller)).isEqualTo(1);
        assertThat(audit("MATCH_CONFIRMED", seller)).isZero();
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void a_manual_match_of_a_pair_that_was_suggested_confirms_that_suggestion() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(helper, quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        UUID suggestion = suggestion(order, payment);
        MatchingService.ApplyResult r = f.matcher.matchManually(tenant, order, payment, seller);
        assertThat(r.matchId()).isEqualTo(suggestion);
        assertThat(audit("MATCH_CONFIRMED", seller)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM match WHERE tenant_id = ?", tenant, null)).isEqualTo(1);
    }

    @Test
    void a_manual_match_refuses_what_cannot_be_matched_and_changes_nothing() {
        UUID open = order("O-1", "Ana Kamga", PHONE, 5000);
        UUID free = payment(seller, quiet.sms(1000, "Ana Kamga", PHONE, null));
        MatchWorld other = new MatchWorld();
        UUID sellerOfOther = MatchingFixture.user(other.tenant, "SELLER");

        UUID unparsed = payment(seller, "Hello, I paid you this morning, thanks");
        UUID matched = payment(seller, quiet.sms(1500, "Ana Kamga", PHONE, null));
        f.matcher.matchManually(tenant, open, matched, seller);
        UUID setAside = payment(seller, quiet.sms(1200, "Ana Kamga", PHONE, null));
        assertThat(f.matcher.markUnmatched(tenant, setAside, seller).done()).isTrue();
        UUID review = w.orderWithoutTotal("NEEDS_REVIEW");
        UUID cancelled = w.order(5000, "PENDING_PAYMENT", "X-1", null);
        MatchWorld.forced(c -> MatchWorld.run(c, "UPDATE orders SET status = 'CANCELLED' WHERE id = ?", cancelled));
        UUID paid = w.order(5000, "PENDING_PAYMENT", "X-2", null);
        MatchWorld.forced(c -> MatchWorld.run(c, "UPDATE orders SET status = 'PAID' WHERE id = ?", paid));
        UUID euro = w.order(5000, "PENDING_PAYMENT", "X-3", null, "EUR", java.time.Instant.now());
        String before = MatchInvariants.fingerprint(tenant);

        assertThat(f.matcher.matchManually(tenant, open, free, helper).reason()).as("a helper").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.matchManually(tenant, open, free, sellerOfOther).reason()).as("a seller of another tenant").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.matchManually(tenant, UUID.randomUUID(), free, seller).reason()).as("unknown order").isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(f.matcher.matchManually(tenant, open, UUID.randomUUID(), seller).reason()).as("unknown payment").isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(f.matcher.matchManually(other.tenant, open, free, sellerOfOther).reason()).as("another tenant's ids").isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(f.matcher.matchManually(tenant, open, unparsed, seller).reason()).as("an unparsed payment").isEqualTo(MatchingService.Reason.PAYMENT_NOT_FREE);
        assertThat(f.matcher.matchManually(tenant, open, matched, seller).reason()).as("a matched payment").isEqualTo(MatchingService.Reason.PAYMENT_NOT_FREE);
        assertThat(f.matcher.matchManually(tenant, open, setAside, seller).reason()).as("a payment set aside").isEqualTo(MatchingService.Reason.PAYMENT_NOT_FREE);
        assertThat(f.matcher.matchManually(tenant, review, free, seller).reason()).as("an order that needs review").isEqualTo(MatchingService.Reason.ORDER_NOT_OPEN);
        assertThat(f.matcher.matchManually(tenant, cancelled, free, seller).reason()).as("a cancelled order").isEqualTo(MatchingService.Reason.ORDER_NOT_OPEN);
        assertThat(f.matcher.matchManually(tenant, paid, free, seller).reason()).as("a paid order").isEqualTo(MatchingService.Reason.ORDER_NOT_OPEN);
        assertThat(f.matcher.matchManually(tenant, euro, free, seller).reason()).as("another currency").isEqualTo(MatchingService.Reason.CURRENCY_MISMATCH);
        assertThat(MatchInvariants.fingerprint(tenant)).as("nothing changed").isEqualTo(before);
    }

    @Test
    void a_duplicate_payment_is_refused_by_hand_too() {
        UUID order = order("O-1", "Ana Kamga", PHONE, 5000);
        String text = quiet.sms(5000, "Ana Kamga", PHONE, null);
        payment(seller, text);
        UUID duplicate = payment(seller, text);
        assertThat(MatchingFixture.status("payment", duplicate)).isEqualTo("DUPLICATE");
        String before = MatchInvariants.fingerprint(tenant);
        assertThat(f.matcher.matchManually(tenant, order, duplicate, seller).reason()).isEqualTo(MatchingService.Reason.PAYMENT_NOT_FREE);
        assertThat(MatchInvariants.fingerprint(tenant)).isEqualTo(before);
    }

    @Test
    void a_seller_action_needs_its_arguments_and_logs_only_ids_and_codes() {
        UUID suggestion = perfectSuggestion();
        assertThatNullPointerException().isThrownBy(() -> f.matcher.confirm(tenant, null, seller)).withMessage("matchId");
        assertThatNullPointerException().isThrownBy(() -> f.matcher.confirm(tenant, suggestion, null)).withMessage("actor");
        assertThatNullPointerException().isThrownBy(() -> f.matcher.reject(tenant, null, seller)).withMessage("matchId");
        assertThatNullPointerException().isThrownBy(() -> f.matcher.reject(tenant, suggestion, null)).withMessage("actor");
        assertThatNullPointerException().isThrownBy(() -> f.matcher.matchManually(tenant, UUID.randomUUID(), UUID.randomUUID(), null)).withMessage("actor");
        try (LogCapture logs = new LogCapture()) {
            f.matcher.reject(tenant, suggestion, helper);
            f.matcher.reject(tenant, suggestion, seller);
            f.matcher.confirm(tenant, suggestion, seller);
            String log = logs.text();
            assertThat(log).contains("seller action refused", "action=REJECT", "NOT_A_SELLER", "suggestion rejected", "action=CONFIRM", "NOT_SUGGESTED", suggestion.toString());
            assertThat(log).doesNotContain("Ana").doesNotContain("Kamga").doesNotContain("A-1042").doesNotContain("999 000");
        }
    }
}
