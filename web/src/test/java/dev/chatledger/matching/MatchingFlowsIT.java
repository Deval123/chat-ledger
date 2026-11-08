package dev.chatledger.matching;

import dev.chatledger.orders.IngestionResult;
import dev.chatledger.payments.PaymentIngestionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.count;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flows from a message to a ledger posting, through the real ingestion services and the matcher: automatic, suggested, partial,
 * over-payment, and the kinds of record that must never be matched automatically (or at all). Every ledger entry is asserted exactly.
 * All names and numbers are synthetic.
 *
 * <p>Requirements: 3.1 to 3.6, 4.1, 4.4, 2.4. Task 6.2.
 */
class MatchingFlowsIT {

    private final MatchingFixture f = new MatchingFixture();
    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final UUID seller = MatchingFixture.user(tenant, "SELLER");

    private UUID order(String ref, String name, String phone, long total) {
        IngestionResult r = f.orders.ingest(tenant, seller, id(), MatchingFixture.orderText(ref, name, phone, total));
        assertThat(r.outcome()).isEqualTo(IngestionResult.Outcome.CREATED);
        return r.orderId();
    }

    private PaymentIngestionResult payment(UUID actor, String text) {
        PaymentIngestionResult r = f.payments.ingest(tenant, actor, id(), text);
        assertThat(r.stored()).isTrue();
        return r;
    }

    private long matches(String state) {
        return count("SELECT count(*) FROM match WHERE tenant_id = ? AND state = ?", tenant, state);
    }

    // ── Automatic ──────────────────────────────────────────────────────────

    @Test
    void a_payment_that_clearly_pays_an_order_is_matched_and_posted_when_it_arrives() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        assertThat(f.repository.forOrder(tenant, order)).as("nothing to match yet").isEmpty();
        PaymentIngestionResult payment = payment(seller, f.sms(5000, "Ana Kamga", PHONE, "A-1042"));

        MatchRepository.Stored m = f.repository.forPayment(tenant, payment.paymentId()).get(0);
        assertThat(m.state()).isEqualTo("AUTO");
        assertThat(m.amountMinor()).isEqualTo(5000L);
        assertThat(m.excessMinor()).isZero();
        assertThat(m.score()).isEqualTo(100);
        assertThat(m.reasonsJson()).contains("AMOUNT_EXACT", "REFERENCE_MATCH", "IDENTITY_PHONE", "TIME_IN_WINDOW").doesNotContain("Ana").doesNotContain("A-1042");
        assertThat(m.decidedBy()).isNull();
        assertThat(m.ledgerTxnId()).isNotNull();
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
        assertThat(MatchingFixture.status("payment", payment.paymentId())).isEqualTo("MATCHED");
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000",
                "PAYMENT_MATCHED 1000 D5000", "PAYMENT_MATCHED 1100 C5000");
        assertThat(MatchingFixture.trialBalance(tenant)).isZero();
        assertThat(MatchingFixture.balance(tenant, "1100")).as("nothing is owed any more").isZero();
        assertThat(MatchingFixture.balance(tenant, "1000")).isEqualTo(5000);
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'MATCH_AUTO' AND subject = ?", tenant, m.id().toString())).isEqualTo(1);
    }

    @Test
    void the_same_pair_is_matched_when_the_order_arrives_second() {
        PaymentIngestionResult payment = payment(seller, f.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        assertThat(f.repository.forPayment(tenant, payment.paymentId())).isEmpty();
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);

        assertThat(f.repository.forOrder(tenant, order)).extracting(MatchRepository.Stored::state).containsExactly("AUTO");
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
        assertThat(MatchingFixture.status("payment", payment.paymentId())).isEqualTo("MATCHED");
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000",
                "PAYMENT_MATCHED 1000 D5000", "PAYMENT_MATCHED 1100 C5000");
    }

    @Test
    void a_payment_recorded_by_a_helper_is_only_ever_suggested() {
        UUID helper = MatchingFixture.user(tenant, "HELPER");
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        PaymentIngestionResult payment = payment(helper, f.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        List<MatchRepository.Stored> rows = f.repository.forPayment(tenant, payment.paymentId());
        assertThat(rows).extracting(MatchRepository.Stored::state).containsExactly("SUGGESTED");
        assertThat(rows.get(0).score()).as("a perfect score, and still not automatic").isEqualTo(100);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
        assertThat(MatchingFixture.status("payment", payment.paymentId())).isEqualTo("RECEIVED");
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000");
    }

    @Test
    void the_same_helper_payment_would_have_been_automatic_from_a_seller() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        payment(seller, f.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
    }

    @Test
    void with_the_kill_switch_off_a_perfect_pair_is_only_suggested() {
        MatchingFixture off = new MatchingFixture(new MatchConfig(85, 60, false, 200, 10), MatchingService.StepHook.NONE, true);
        IngestionResult o = off.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("A-1042", "Ana Kamga", PHONE, 5000));
        PaymentIngestionResult p = off.payments.ingest(tenant, seller, id(), off.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        assertThat(off.repository.forPayment(tenant, p.paymentId())).extracting(MatchRepository.Stored::state).containsExactly("SUGGESTED");
        assertThat(MatchingFixture.status("orders", o.orderId())).isEqualTo("PENDING_PAYMENT");
    }

    // ── Suggested ──────────────────────────────────────────────────────────

    @Test
    void a_payment_with_a_good_score_but_no_reference_is_suggested_and_changes_nothing_else() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        PaymentIngestionResult payment = payment(seller, f.sms(5000, "Ana Kamga", PHONE, null));
        List<MatchRepository.Stored> rows = f.repository.forPayment(tenant, payment.paymentId());
        assertThat(rows).hasSize(1);
        MatchRepository.Stored s = rows.get(0);
        assertThat(s.state()).isEqualTo("SUGGESTED");
        assertThat(s.score()).isEqualTo(75);
        assertThat(s.amountMinor()).isNull();
        assertThat(s.ledgerTxnId()).isNull();
        assertThat(s.reasonsJson()).contains("AMOUNT_EXACT", "IDENTITY_PHONE", "TIME_IN_WINDOW").doesNotContain("REFERENCE");
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
        assertThat(MatchingFixture.status("payment", payment.paymentId())).isEqualTo("RECEIVED");
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000");
    }

    @Test
    void a_payment_with_a_weak_score_is_not_even_suggested() {
        order("A-1042", "Bea Nkomo", "+999 000 000 9999", 5000);
        PaymentIngestionResult payment = payment(seller, f.sms(777, "Ana Kamga", PHONE, null));
        assertThat(f.repository.forPayment(tenant, payment.paymentId())).isEmpty();
    }

    @Test
    void two_orders_with_the_same_reference_make_the_reference_prove_nothing() {
        order("A-1042", "Ana Kamga", PHONE, 5000);
        order("a-1042", "Ana Kamga", PHONE, 5000);
        PaymentIngestionResult payment = payment(seller, f.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        List<MatchRepository.Stored> rows = f.repository.forPayment(tenant, payment.paymentId());
        assertThat(rows).hasSize(2).allSatisfy(r -> {
            assertThat(r.state()).isEqualTo("SUGGESTED");
            assertThat(r.reasonsJson()).contains("REFERENCE_AMBIGUOUS").doesNotContain("REFERENCE_MATCH");
        });
        assertThat(matches("AUTO")).isZero();
    }

    @Test
    void running_the_engine_again_stores_nothing_new() {
        order("A-1042", "Ana Kamga", PHONE, 5000);
        PaymentIngestionResult payment = payment(seller, f.sms(5000, "Ana Kamga", PHONE, null));
        assertThat(f.matcher.onPaymentRecorded(tenant, payment.paymentId()).suggestions()).isZero();
        assertThat(matches("SUGGESTED")).isEqualTo(1);
    }

    @Test
    void a_pair_the_seller_rejected_is_not_suggested_again() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
        PaymentIngestionResult payment = quiet.payments.ingest(tenant, seller, id(), quiet.sms(5000, "Ana Kamga", PHONE, null));
        w.rejected(order, payment.paymentId(), true);
        MatchingService.Run run = f.matcher.onPaymentRecorded(tenant, payment.paymentId());
        assertThat(run.kind()).as("a pair the seller rejected is not even a candidate").isEqualTo(MatchingService.RunKind.NOTHING);
        assertThat(run.suggestions()).isZero();
        assertThat(matches("SUGGESTED")).isZero();
    }

    // ── Partial and over-payment (the seller's decision) ───────────────────

    @Test
    void a_partial_payment_is_suggested_then_confirmed_and_the_order_stays_partially_paid() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        PaymentIngestionResult first = payment(seller, f.sms(2000, "Ana Kamga", PHONE, "A-1042"));
        assertThat(f.repository.forPayment(tenant, first.paymentId())).extracting(MatchRepository.Stored::state).containsExactly("SUGGESTED");

        MatchingService.ApplyResult r = f.matcher.apply(tenant, order, first.paymentId(), MatchingService.Mode.CONFIRM, seller);
        assertThat(r.outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        assertThat(r.appliedMinor()).isEqualTo(2000);
        assertThat(r.excessMinor()).isZero();
        assertThat(r.orderStatus()).isEqualTo("PARTIALLY_PAID");
        MatchRepository.Stored m = f.repository.find(tenant, r.matchId()).orElseThrow();
        assertThat(m.state()).isEqualTo("CONFIRMED");
        assertThat(m.decidedBy()).isEqualTo(seller);
        assertThat(m.decidedAt()).isNotNull();
        assertThat(f.repository.forPayment(tenant, first.paymentId())).as("the suggestion became the match").hasSize(1);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PARTIALLY_PAID");
        assertThat(MatchingFixture.balance(tenant, "1100")).as("3,000 is still owed").isEqualTo(3000);

        PaymentIngestionResult second = payment(seller, f.sms(3000, "Ana Kamga", PHONE, "A-1042"));
        assertThat(f.repository.forPayment(tenant, second.paymentId()).get(0).state()).as("exact on the last balance, with every floor").isEqualTo("AUTO");
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000",
                "PAYMENT_MATCHED 1000 D2000", "PAYMENT_MATCHED 1000 D3000", "PAYMENT_MATCHED 1100 C2000", "PAYMENT_MATCHED 1100 C3000");
        assertThat(MatchingFixture.balance(tenant, "1100")).isZero();
        assertThat(MatchingFixture.trialBalance(tenant)).isZero();
    }

    @Test
    void an_over_payment_pays_the_order_and_records_the_excess_as_customer_credit() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        PaymentIngestionResult payment = payment(seller, f.sms(8000, "Ana Kamga", PHONE, "A-1042"));
        assertThat(f.repository.forPayment(tenant, payment.paymentId())).extracting(MatchRepository.Stored::state).containsExactly("SUGGESTED");

        MatchingService.ApplyResult r = f.matcher.apply(tenant, order, payment.paymentId(), MatchingService.Mode.CONFIRM, seller);
        assertThat(r.appliedMinor()).isEqualTo(5000);
        assertThat(r.excessMinor()).isEqualTo(3000);
        assertThat(r.orderStatus()).isEqualTo("PAID");
        assertThat(f.repository.find(tenant, r.matchId()).orElseThrow().excessMinor()).isEqualTo(3000);
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000",
                "PAYMENT_MATCHED 1000 D8000", "PAYMENT_MATCHED 1100 C5000", "PAYMENT_MATCHED 2100 C3000");
        assertThat(MatchingFixture.balance(tenant, "2100")).as("a liability: 3,000 owed to the customer").isEqualTo(-3000);
        assertThat(MatchingFixture.balance(tenant, "1100")).isZero();
        assertThat(MatchingFixture.trialBalance(tenant)).isZero();
    }

    @Test
    void a_payment_above_the_balance_of_a_partly_paid_order_only_applies_the_balance() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        PaymentIngestionResult first = payment(seller, f.sms(2000, "Ana Kamga", PHONE, "A-1042"));
        f.matcher.apply(tenant, order, first.paymentId(), MatchingService.Mode.CONFIRM, seller);
        PaymentIngestionResult second = payment(seller, f.sms(4000, "Ana Kamga", PHONE, "A-1042"));
        MatchingService.ApplyResult r = f.matcher.apply(tenant, order, second.paymentId(), MatchingService.Mode.CONFIRM, seller);
        assertThat(r.appliedMinor()).isEqualTo(3000);
        assertThat(r.excessMinor()).isEqualTo(1000);
        assertThat(MatchingFixture.ledger(tenant)).contains("PAYMENT_MATCHED 1000 D4000", "PAYMENT_MATCHED 1100 C3000", "PAYMENT_MATCHED 2100 C1000");
        assertThat(MatchingFixture.trialBalance(tenant)).isZero();
    }

    @Test
    void a_payment_that_arrives_when_nothing_is_left_to_pay_is_refused_not_turned_into_credit() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        payment(seller, f.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        PaymentIngestionResult late = payment(seller, f.sms(1000, "Ana Kamga", PHONE, "A-1042"));
        MatchingService.ApplyResult r = f.matcher.apply(tenant, order, late.paymentId(), MatchingService.Mode.CONFIRM, seller);
        assertThat(r.outcome()).isEqualTo(MatchingService.Outcome.NOT_APPLICABLE);
        assertThat(r.reason()).isIn(MatchingService.Reason.ORDER_NOT_OPEN, MatchingService.Reason.NOTHING_LEFT_TO_PAY);
        assertThat(MatchingFixture.status("payment", late.paymentId())).isEqualTo("RECEIVED");
        assertThat(MatchingFixture.balance(tenant, "2100")).isZero();
    }

    // ── Records that are never matched automatically, or at all ────────────

    @Test
    void an_unverified_payment_is_only_suggested_and_a_sellers_confirmation_then_posts_it() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        PaymentIngestionResult payment = payment(seller, f.forwarded(5000, "Ana Kamga", "1234"));
        assertThat(payment.outcome()).isEqualTo(PaymentIngestionResult.Outcome.CREATED_UNVERIFIED);
        List<MatchRepository.Stored> rows = f.repository.forPayment(tenant, payment.paymentId());
        assertThat(rows).extracting(MatchRepository.Stored::state).containsExactly("SUGGESTED");
        assertThat(rows.get(0).score()).isLessThan(85);
        assertThat(MatchingFixture.ledger(tenant)).as("a suggestion posts nothing").hasSize(2);

        MatchingService.ApplyResult r = f.matcher.apply(tenant, order, payment.paymentId(), MatchingService.Mode.CONFIRM, seller);
        assertThat(r.outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        assertThat(MatchingFixture.status("payment", payment.paymentId())).isEqualTo("MATCHED");
        assertThat(MatchingFixture.ledger(tenant)).contains("PAYMENT_MATCHED 1000 D5000", "PAYMENT_MATCHED 1100 C5000");
    }

    @Test
    void an_unverified_payment_is_never_automatic_even_called_directly() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
        PaymentIngestionResult payment = quiet.payments.ingest(tenant, seller, id(), quiet.forwarded(5000, "Ana Kamga", "1234"));
        MatchingService.ApplyResult r = f.matcher.apply(tenant, order, payment.paymentId(), MatchingService.Mode.AUTO, null);
        assertThat(r.outcome()).isEqualTo(MatchingService.Outcome.DEGRADED_TO_SUGGESTION);
        assertThat(MatchingFixture.status("payment", payment.paymentId())).isEqualTo("UNVERIFIED");
        assertThat(MatchingFixture.ledger(tenant)).hasSize(2);
        assertThat(matches("AUTO") + matches("CONFIRMED")).isZero();
    }

    @Test
    void a_duplicate_payment_is_never_matched_and_never_posted() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        String text = f.sms(5000, "Ana Kamga", PHONE, null);
        PaymentIngestionResult original = payment(seller, text);
        PaymentIngestionResult duplicate = payment(seller, text);
        assertThat(duplicate.outcome()).isEqualTo(PaymentIngestionResult.Outcome.CREATED_DUPLICATE_TX);
        // the original is merely suggested (no reference), so the order is still open for the duplicate to try
        MatchingService.ApplyResult r = f.matcher.apply(tenant, order, duplicate.paymentId(), MatchingService.Mode.CONFIRM, seller);
        assertThat(r.outcome()).isEqualTo(MatchingService.Outcome.NOT_APPLICABLE);
        assertThat(r.reason()).isEqualTo(MatchingService.Reason.PAYMENT_NOT_FREE);
        assertThat(f.matcher.onPaymentRecorded(tenant, duplicate.paymentId()).kind()).isEqualTo(MatchingService.RunKind.SKIPPED);
        assertThat(f.repository.forPayment(tenant, duplicate.paymentId())).isEmpty();
        assertThat(MatchingFixture.status("payment", duplicate.paymentId())).isEqualTo("DUPLICATE");
        assertThat(MatchingFixture.ledger(tenant)).as("no payment posting at all").containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000");
        assertThat(original.paymentId()).isNotEqualTo(duplicate.paymentId());
    }

    @Test
    void an_unparsed_payment_and_an_order_that_needs_review_are_never_matched() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        PaymentIngestionResult unparsed = payment(seller, "Hello, I paid you this morning, thanks");
        assertThat(unparsed.outcome()).isEqualTo(PaymentIngestionResult.Outcome.CREATED_UNPARSED);
        assertThat(f.matcher.apply(tenant, order, unparsed.paymentId(), MatchingService.Mode.CONFIRM, seller).reason())
                .isEqualTo(MatchingService.Reason.PAYMENT_NOT_FREE);
        UUID review = w.orderWithoutTotal("NEEDS_REVIEW");
        UUID free = payment(seller, f.sms(5000, "Cy O'Neil", "+999 000 000 4321", null)).paymentId();
        assertThat(f.matcher.apply(tenant, review, free, MatchingService.Mode.CONFIRM, seller).reason()).isEqualTo(MatchingService.Reason.ORDER_NOT_OPEN);
        assertThat(MatchingFixture.ledger(tenant)).hasSize(2);
    }

    @Test
    void a_different_currency_is_never_matched() {
        UUID eur = w.order(5000, "PENDING_PAYMENT", "A-9", null, "EUR", java.time.Instant.now());
        UUID payment = payment(seller, f.sms(5000, "Ana Kamga", PHONE, "A-9")).paymentId();
        assertThat(f.matcher.apply(tenant, eur, payment, MatchingService.Mode.CONFIRM, seller).reason()).isEqualTo(MatchingService.Reason.CURRENCY_MISMATCH);
        assertThat(f.repository.forPayment(tenant, payment)).isEmpty();
    }

    // ── Other suggestions are retired when a match is made ─────────────────

    @Test
    void matching_a_payment_retires_the_other_suggestions_for_it_and_for_a_paid_order() {
        UUID first = order("A-1", "Ana Kamga", PHONE, 5000);
        UUID second = order("A-2", "Ana Kamga", PHONE, 5000);
        PaymentIngestionResult a = payment(seller, f.sms(5000, "Ana Kamga", PHONE, null));
        PaymentIngestionResult b = payment(seller, f.sms(5000, "Ana Kamga", PHONE, null));
        assertThat(matches("SUGGESTED")).as("two orders for each of two payments").isGreaterThanOrEqualTo(3);

        MatchingService.ApplyResult r = f.matcher.apply(tenant, first, a.paymentId(), MatchingService.Mode.CONFIRM, seller);
        assertThat(r.outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        // payment a's other suggestion, and order first's suggestion from payment b, are retired by the system (no decider)
        assertThat(f.repository.forPayment(tenant, a.paymentId())).extracting(MatchRepository.Stored::state)
                .containsOnly("CONFIRMED", "REJECTED");
        assertThat(f.repository.forOrder(tenant, first)).filteredOn(m -> !m.id().equals(r.matchId())).allSatisfy(m -> {
            assertThat(m.state()).isEqualTo("REJECTED");
            assertThat(m.decidedBy()).isNull();
            assertThat(m.decidedAt()).isNotNull();
        });
        assertThat(f.repository.forOrder(tenant, second)).extracting(MatchRepository.Stored::state).as("the second order is still open to payment b")
                .contains("SUGGESTED");
        assertThat(f.repository.forPayment(tenant, b.paymentId())).extracting(MatchRepository.Stored::state).contains("SUGGESTED");
    }

    // ── Refusals and argument checks ───────────────────────────────────────

    @Test
    void unknown_ids_and_another_tenants_ids_are_not_found() {
        UUID order = order("A-1", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(seller, f.sms(5000, "Ana Kamga", PHONE, null)).paymentId();
        MatchWorld other = new MatchWorld();
        UUID otherUser = other.user;
        assertThat(f.matcher.apply(tenant, UUID.randomUUID(), payment, MatchingService.Mode.CONFIRM, seller).reason()).isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(f.matcher.apply(tenant, order, UUID.randomUUID(), MatchingService.Mode.CONFIRM, seller).reason()).isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(f.matcher.apply(other.tenant, order, payment, MatchingService.Mode.CONFIRM, otherUser).reason()).as("another tenant's rows are invisible")
                .isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("RECEIVED");
        assertThat(MatchingFixture.ledger(other.tenant)).isEmpty();
    }

    @Test
    void a_confirmation_names_who_decided_and_an_automatic_match_names_nobody() {
        UUID order = order("A-1", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(seller, f.sms(5000, "Ana Kamga", PHONE, null)).paymentId();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.matcher.apply(tenant, order, payment, MatchingService.Mode.CONFIRM, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.matcher.apply(tenant, order, payment, MatchingService.Mode.AUTO, seller))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(matches("CONFIRMED") + matches("AUTO")).isZero();
    }

    @Test
    void an_automatic_apply_of_a_helpers_payment_is_turned_into_a_suggestion_even_if_called_directly() {
        UUID helper = MatchingFixture.user(tenant, "HELPER");
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
        PaymentIngestionResult payment = quiet.payments.ingest(tenant, helper, id(), quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        MatchingService.ApplyResult r = f.matcher.apply(tenant, order, payment.paymentId(), MatchingService.Mode.AUTO, null);
        assertThat(r.outcome()).isEqualTo(MatchingService.Outcome.DEGRADED_TO_SUGGESTION);
        assertThat(r.reason()).isEqualTo(MatchingService.Reason.AUTO_NOT_ELIGIBLE);
        assertThat(MatchingFixture.status("payment", payment.paymentId())).isEqualTo("RECEIVED");
        assertThat(MatchingFixture.ledger(tenant)).hasSize(2);
    }

    @Test
    void rows_that_an_older_version_left_inconsistent_are_refused_cleanly_not_matched() throws Exception {
        // An order that is "partly paid" although active matches already cover its whole total: the new triggers cannot create this, so the
        // test writes it with triggers off (session_replication_role = replica, which only a superuser may set).
        UUID order = w.order(5000, "PARTIALLY_PAID", null, null);
        UUID covered = w.payment(5000, "MATCHED");
        UUID free = w.payment(1000, "RECEIVED");
        try (java.sql.Connection c = MatchingFixture.DB.superDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (java.sql.Statement s = c.createStatement()) {
                s.execute("SET LOCAL session_replication_role = replica");
            }
            UUID txn = UUID.randomUUID();
            MatchWorld.run(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'LEGACY', 'X', ?)", txn, tenant, UUID.randomUUID());
            MatchWorld.run(c, "INSERT INTO match(id, tenant_id, order_id, payment_id, amount_minor, excess_minor, score, reasons_json, state, ledger_txn_id, decided_by, decided_at) "
                    + "VALUES (?, ?, ?, ?, 5000, 0, 90, '[]', 'CONFIRMED', ?, ?, now())", UUID.randomUUID(), tenant, order, covered, txn, w.user);
            c.commit();
        }
        MatchingService.ApplyResult r = f.matcher.apply(tenant, order, free, MatchingService.Mode.CONFIRM, seller);
        assertThat(r.outcome()).isEqualTo(MatchingService.Outcome.NOT_APPLICABLE);
        assertThat(r.reason()).isEqualTo(MatchingService.Reason.NOTHING_LEFT_TO_PAY);
        assertThat(MatchingFixture.status("payment", free)).isEqualTo("RECEIVED");
    }

    @Test
    void only_the_most_recent_candidates_up_to_the_cap_are_scored() {
        MatchingFixture small = new MatchingFixture(new MatchConfig(85, 60, true, 10, 10), MatchingService.StepHook.NONE, false);
        MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
        UUID oldest = w.order(5000, "PENDING_PAYMENT", "OLD-1", null, "XAF", java.time.Instant.now().minus(java.time.Duration.ofDays(2)));
        for (int i = 0; i < 10; i++) {
            w.order(1111 + i, "PENDING_PAYMENT", "NEW-" + i, null);
        }
        PaymentIngestionResult payment = quiet.payments.ingest(tenant, seller, id(), quiet.sms(5000, "Ana Kamga", PHONE, "OLD-1"));
        assertThat(small.matcher.onPaymentRecorded(tenant, payment.paymentId()).kind()).as("the oldest order is beyond the cap of ten").isEqualTo(MatchingService.RunKind.NOTHING);
        assertThat(matches("SUGGESTED")).isZero();
        assertThat(quiet.matcher.onPaymentRecorded(tenant, payment.paymentId()).kind()).isEqualTo(MatchingService.RunKind.SUGGESTED);
        assertThat(f.repository.forOrder(tenant, oldest)).hasSize(1);
    }

    @Test
    void confirming_one_partial_payment_leaves_the_other_suggestions_for_the_still_open_order_and_audits_the_decision() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        PaymentIngestionResult a = payment(seller, f.sms(2000, "Ana Kamga", PHONE, "A-1042"));
        PaymentIngestionResult b = payment(seller, f.sms(1000, "Ana Kamga", PHONE, "A-1042"));
        assertThat(matches("SUGGESTED")).isEqualTo(2);
        MatchingService.ApplyResult r = f.matcher.apply(tenant, order, a.paymentId(), MatchingService.Mode.CONFIRM, seller);
        assertThat(r.orderStatus()).isEqualTo("PARTIALLY_PAID");
        assertThat(f.repository.forPayment(tenant, b.paymentId())).extracting(MatchRepository.Stored::state).containsExactly("SUGGESTED");
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'MATCH_CONFIRMED' AND subject = ?", tenant, r.matchId().toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'MATCH_CONFIRMED' AND actor = ?", tenant, seller)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'MATCH_AUTO'", tenant, null)).isZero();
    }
}
