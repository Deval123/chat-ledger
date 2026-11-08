package dev.chatledger.matching;

import dev.chatledger.tenancy.DbTime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.count;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seller's unmatch: the posting of the match is reversed (a new transaction that mirrors it, the original untouched), the match
 * becomes REVERSED with who and when, the order is recomputed from the matches that remain, the payment is free again, and the pair
 * is remembered so that the engine never puts it back. Every ledger entry is asserted exactly. All names and numbers are synthetic.
 *
 * <p>Requirements: 3.7, 4.2, 4.3, 10. Task 6.3.
 */
class UnmatchIT {

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
        return quiet.payments.ingest(tenant, actor, id(), text).paymentId();
    }

    private UUID manual(UUID order, UUID payment) {
        MatchingService.ApplyResult r = f.matcher.matchManually(tenant, order, payment, seller);
        assertThat(r.outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        return r.matchId();
    }

    @Test
    void unmatching_an_automatic_match_reverses_its_posting_frees_both_records_and_remembers_the_pair() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = f.payments.ingest(tenant, seller, id(), f.sms(5000, "Ana Kamga", PHONE, "A-1042")).paymentId();
        MatchRepository.Stored before = f.repository.forPayment(tenant, payment).get(0);
        assertThat(before.state()).isEqualTo("AUTO");
        f.clock.advance(Duration.ofMinutes(5));

        MatchingService.Decision d = f.matcher.unmatch(tenant, before.id(), seller);

        assertThat(d.done()).isTrue();
        assertThat(d.subjectId()).isEqualTo(before.id());
        assertThat(d.orderStatus()).isEqualTo("PENDING_PAYMENT");
        MatchRepository.Stored after = f.repository.find(tenant, before.id()).orElseThrow();
        assertThat(after.state()).isEqualTo("REVERSED");
        assertThat(after.decidedBy()).as("who").isEqualTo(seller);
        assertThat(after.decidedAt()).as("when").isEqualTo(DbTime.micros(f.clock.instant()));
        assertThat(after.ledgerTxnId()).as("the original posting stays the match's posting").isEqualTo(before.ledgerTxnId());
        assertThat(after.amountMinor()).isEqualTo(5000L);
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000",
                "PAYMENT_MATCHED 1000 D5000", "PAYMENT_MATCHED 1100 C5000", "REVERSAL 1000 C5000", "REVERSAL 1100 D5000");
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND reverses_txn_id = ?", tenant, before.ledgerTxnId())).isEqualTo(1);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("RECEIVED");
        assertThat(MatchingFixture.balance(tenant, "1100")).as("the order is owed again").isEqualTo(5000);
        assertThat(MatchingFixture.balance(tenant, "1000")).isZero();
        assertThat(MatchingFixture.rows("SELECT subject FROM audit_log WHERE tenant_id = ? AND action = 'MATCH_REVERSED' AND actor IS NOT NULL", tenant))
                .containsExactly(before.id().toString());
        MatchInvariants.assertHold(tenant);

        // the seller said no to this pair: the engine does not put it back, by any road
        assertThat(f.matcher.onPaymentRecorded(tenant, payment).kind()).isEqualTo(MatchingService.RunKind.NOTHING);
        assertThat(f.matcher.onOrderRecorded(tenant, order).kind()).isEqualTo(MatchingService.RunKind.NOTHING);
        assertThat(f.matcher.apply(tenant, order, payment, MatchingService.Mode.AUTO, null).reason()).isEqualTo(MatchingService.Reason.SELLER_DECLINED);
        assertThat(f.matcher.rematch(tenant, seller).applied()).isZero();
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");

        // but the seller may match it again by hand: a new match, a new posting, and the reversal stays one
        UUID again = manual(order, payment);
        assertThat(again).isNotEqualTo(before.id());
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND kind = 'REVERSAL'", tenant, null)).isEqualTo(1);
        MatchInvariants.assertHold(tenant);
        assertThat(f.matcher.unmatch(tenant, again, seller).done()).isTrue();
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void unmatching_a_match_with_an_excess_reverses_the_credit_too() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(seller, quiet.sms(7000, "Ana Kamga", PHONE, "A-1042"));
        UUID match = manual(order, payment);
        assertThat(MatchingFixture.balance(tenant, "2100")).as("credit is a liability: negative net debit").isEqualTo(-2000);

        assertThat(f.matcher.unmatch(tenant, match, seller).done()).isTrue();

        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000",
                "PAYMENT_MATCHED 1000 D7000", "PAYMENT_MATCHED 1100 C5000", "PAYMENT_MATCHED 2100 C2000",
                "REVERSAL 1000 C7000", "REVERSAL 1100 D5000", "REVERSAL 2100 D2000");
        assertThat(MatchingFixture.balance(tenant, "2100")).isZero();
        assertThat(MatchingFixture.trialBalance(tenant)).isZero();
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void unmatching_one_of_two_payments_leaves_the_order_partly_paid_and_the_other_match_alone() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID first = payment(seller, quiet.sms(2000, "Ana Kamga", PHONE, "A-1042"));
        UUID second = payment(seller, quiet.sms(3000, "Ana Kamga", PHONE, "A-1042"));
        UUID m1 = manual(order, first);
        UUID m2 = manual(order, second);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");

        MatchingService.Decision d = f.matcher.unmatch(tenant, m1, seller);

        assertThat(d.orderStatus()).isEqualTo("PARTIALLY_PAID");
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PARTIALLY_PAID");
        assertThat(f.repository.appliedMinor(tenant, order)).isEqualTo(3000);
        assertThat(f.repository.find(tenant, m2).orElseThrow().state()).isEqualTo("CONFIRMED");
        assertThat(MatchingFixture.status("payment", first)).isEqualTo("RECEIVED");
        assertThat(MatchingFixture.status("payment", second)).isEqualTo("MATCHED");
        assertThat(MatchingFixture.balance(tenant, "1100")).isEqualTo(2000);
        MatchInvariants.assertHold(tenant);

        assertThat(f.matcher.unmatch(tenant, m2, seller).orderStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
        assertThat(MatchingFixture.balance(tenant, "1100")).isEqualTo(5000);
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void a_payment_without_a_transaction_id_goes_back_to_unverified() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(seller, quiet.forwarded(5000, "Ana Kamga", "1234"));
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("UNVERIFIED");
        UUID match = manual(order, payment);
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("MATCHED");

        assertThat(f.matcher.unmatch(tenant, match, seller).done()).isTrue();

        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("UNVERIFIED");
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void an_unmatch_and_its_refusal_are_logged_with_ids_and_codes_only() {
        order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = f.payments.ingest(tenant, seller, id(), f.sms(5000, "Ana Kamga", PHONE, "A-1042")).paymentId();
        UUID match = f.repository.forPayment(tenant, payment).get(0).id();
        try (dev.chatledger.orders.LogCapture logs = new dev.chatledger.orders.LogCapture()) {
            f.matcher.unmatch(tenant, match, helper);
            f.matcher.unmatch(tenant, match, seller);
            f.matcher.unmatch(tenant, match, seller);
            String log = logs.text();
            assertThat(log).contains("seller action refused", "action=UNMATCH", "NOT_A_SELLER", "NOT_ACTIVE", "match reversed", "order=PENDING_PAYMENT", match.toString());
            assertThat(log).doesNotContain("Ana").doesNotContain("Kamga").doesNotContain("A-1042").doesNotContain("999 000");
        }
    }

    @Test
    void unmatching_undoes_only_its_own_match() {
        UUID a = order("A-1", "Ana Kamga", PHONE, 5000);
        UUID b = order("B-2", "Bea Nkomo", "+999 000 000 5678", 4000);
        UUID pa = f.payments.ingest(tenant, seller, id(), f.sms(5000, "Ana Kamga", PHONE, "A-1")).paymentId();
        UUID pb = f.payments.ingest(tenant, seller, id(), f.sms(4000, "Bea Nkomo", "+999 000 000 5678", "B-2")).paymentId();
        UUID ma = f.repository.forPayment(tenant, pa).get(0).id();
        UUID mb = f.repository.forPayment(tenant, pb).get(0).id();

        assertThat(f.matcher.unmatch(tenant, ma, seller).done()).isTrue();

        assertThat(MatchingFixture.status("orders", a)).isEqualTo("PENDING_PAYMENT");
        assertThat(MatchingFixture.status("orders", b)).isEqualTo("PAID");
        assertThat(MatchingFixture.status("payment", pb)).isEqualTo("MATCHED");
        assertThat(f.repository.find(tenant, mb).orElseThrow().state()).isEqualTo("AUTO");
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void unmatching_refuses_everything_that_is_not_an_active_match_for_an_enabled_seller_and_changes_nothing() {
        order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = f.payments.ingest(tenant, seller, id(), f.sms(5000, "Ana Kamga", PHONE, "A-1042")).paymentId();
        UUID match = f.repository.forPayment(tenant, payment).get(0).id();
        MatchWorld other = new MatchWorld();
        UUID sellerOfOther = MatchingFixture.user(other.tenant, "SELLER");
        UUID disabled = MatchingFixture.user(tenant, "SELLER");
        w.run("UPDATE app_user SET disabled_at = now() WHERE id = ?", disabled);
        String before = MatchInvariants.fingerprint(tenant);

        assertThat(f.matcher.unmatch(tenant, match, helper).reason()).as("a helper").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.unmatch(tenant, match, disabled).reason()).as("a disabled seller").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.unmatch(tenant, match, sellerOfOther).reason()).as("a seller of another tenant").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.unmatch(tenant, match, UUID.randomUUID()).reason()).as("an unknown user").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.unmatch(tenant, UUID.randomUUID(), seller).reason()).as("an unknown match").isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(f.matcher.unmatch(other.tenant, match, sellerOfOther).reason()).as("another tenant's match id").isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(MatchInvariants.fingerprint(tenant)).as("nothing changed").isEqualTo(before);

        assertThat(f.matcher.unmatch(tenant, match, seller).done()).isTrue();
        String reversed = MatchInvariants.fingerprint(tenant);
        assertThat(f.matcher.unmatch(tenant, match, seller).reason()).as("twice").isEqualTo(MatchingService.Reason.NOT_ACTIVE);
        assertThat(MatchInvariants.fingerprint(tenant)).isEqualTo(reversed);
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND kind = 'REVERSAL'", tenant, null)).isEqualTo(1);

        // a suggestion and a rejected suggestion are not active matches either
        order("B-2", "Bea Nkomo", "+999 000 000 5678", 4000);
        UUID p2 = payment(helper, quiet.sms(4000, "Bea Nkomo", "+999 000 000 5678", "B-2"));
        f.matcher.onPaymentRecorded(tenant, p2);
        UUID suggestion = f.repository.forPayment(tenant, p2).get(0).id();
        String suggested = MatchInvariants.fingerprint(tenant);
        assertThat(f.matcher.unmatch(tenant, suggestion, seller).reason()).as("a suggestion").isEqualTo(MatchingService.Reason.NOT_ACTIVE);
        f.matcher.reject(tenant, suggestion, seller);
        String rejected = MatchInvariants.fingerprint(tenant);
        assertThat(f.matcher.unmatch(tenant, suggestion, seller).reason()).as("a rejected suggestion").isEqualTo(MatchingService.Reason.NOT_ACTIVE);
        assertThat(MatchInvariants.fingerprint(tenant)).isEqualTo(rejected);
        assertThat(suggested).isNotEqualTo(rejected);
        // a reversed match cannot be confirmed either
        assertThat(f.matcher.confirm(tenant, match, seller).reason()).isEqualTo(MatchingService.Reason.NOT_SUGGESTED);
    }
}
