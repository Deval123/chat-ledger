package dev.chatledger.matching;

import dev.chatledger.orders.LogCapture;
import dev.chatledger.tenancy.DbTime;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.count;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Setting a payment aside as UNMATCHED and taking it back (only the seller does either, and the engine never does), and the
 * seller's rematch: what each does, who and when it records, everything it refuses, and that a refusal changes nothing.
 * All names and numbers are synthetic.
 *
 * <p>Requirements: 3.4, 3.7, 10. Task 6.3.
 */
class PaymentSetAsideAndRematchIT {

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

    private long audit(String action, UUID actor) {
        return count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = '" + action + "' AND actor = ?", tenant, actor);
    }

    // ── Set aside and take back ────────────────────────────────────────────

    @Test
    void the_seller_sets_a_payment_aside_and_takes_it_back_and_the_engine_never_does_either() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(helper, quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        f.matcher.onPaymentRecorded(tenant, payment);
        UUID suggestion = f.repository.forPayment(tenant, payment).get(0).id();
        assertThat(count("SELECT count(*) FROM payment WHERE tenant_id = ? AND status = 'UNMATCHED'", tenant, null)).as("the engine sets nothing aside").isZero();

        MatchingService.Decision d = f.matcher.markUnmatched(tenant, payment, seller);

        assertThat(d.done()).isTrue();
        assertThat(d.subjectId()).isEqualTo(payment);
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("UNMATCHED");
        MatchRepository.Stored s = f.repository.find(tenant, suggestion).orElseThrow();
        assertThat(s.state()).as("its open suggestions go with it").isEqualTo("REJECTED");
        assertThat(s.decidedBy()).as("rejected by the seller, so never proposed again").isEqualTo(seller);
        assertThat(s.decidedAt()).isEqualTo(DbTime.micros(f.clock.instant()));
        assertThat(audit("PAYMENT_UNMATCHED", seller)).isEqualTo(1);
        assertThat(MatchingFixture.rows("SELECT subject FROM audit_log WHERE tenant_id = ? AND action = 'PAYMENT_UNMATCHED'", tenant)).containsExactly(payment.toString());
        assertThat(MatchingFixture.ledger(tenant)).as("posts nothing").hasSize(2);

        // a payment set aside is not a candidate, for the engine or for a match by hand
        assertThat(f.matcher.onPaymentRecorded(tenant, payment).kind()).isEqualTo(MatchingService.RunKind.SKIPPED);
        assertThat(f.matcher.onOrderRecorded(tenant, order).kind()).isEqualTo(MatchingService.RunKind.NOTHING);
        assertThat(f.matcher.matchManually(tenant, order, payment, seller).reason()).isEqualTo(MatchingService.Reason.PAYMENT_NOT_FREE);
        assertThat(f.matcher.rematch(tenant, seller).examined()).isZero();
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("UNMATCHED");

        MatchingService.Decision back = f.matcher.reopen(tenant, payment, seller);

        assertThat(back.done()).isTrue();
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("RECEIVED");
        assertThat(audit("PAYMENT_REOPENED", seller)).isEqualTo(1);
        assertThat(f.matcher.onPaymentRecorded(tenant, payment).kind()).as("the seller's no to that pair stands").isEqualTo(MatchingService.RunKind.NOTHING);
        assertThat(f.matcher.matchManually(tenant, order, payment, seller).outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void setting_one_payment_aside_rejects_only_its_own_suggestions() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID aside = payment(helper, quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        UUID other = payment(helper, quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        f.matcher.onPaymentRecorded(tenant, aside);
        f.matcher.onPaymentRecorded(tenant, other);
        assertThat(f.repository.forPayment(tenant, other)).extracting(MatchRepository.Stored::state).containsExactly("SUGGESTED");

        assertThat(f.matcher.markUnmatched(tenant, aside, seller).done()).isTrue();

        assertThat(f.repository.forPayment(tenant, aside)).extracting(MatchRepository.Stored::state).containsExactly("REJECTED");
        assertThat(f.repository.forPayment(tenant, other)).extracting(MatchRepository.Stored::state).containsExactly("SUGGESTED");
        assertThat(f.repository.forOrder(tenant, order)).hasSize(2);
    }

    @Test
    void taking_a_payment_back_does_not_reject_a_suggestion_the_seller_never_decided_on() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(seller, quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        assertThat(f.matcher.markUnmatched(tenant, payment, seller).done()).isTrue();
        // a run that read the payment before it was set aside can still store a suggestion for it afterwards
        UUID late = w.suggested(order, payment);

        assertThat(f.matcher.reopen(tenant, payment, seller).done()).isTrue();

        MatchRepository.Stored s = f.repository.find(tenant, late).orElseThrow();
        assertThat(s.state()).isEqualTo("SUGGESTED");
        assertThat(s.decidedBy()).isNull();
    }

    @Test
    void setting_a_payment_aside_refuses_what_it_must_and_changes_nothing() {
        UUID received = payment(seller, quiet.sms(1000, "Ana Kamga", PHONE, null));
        UUID unverified = payment(seller, quiet.forwarded(2000, "Ana Kamga", "1234"));
        UUID order = order("A-1", "Ana Kamga", PHONE, 5000);
        UUID matched = payment(seller, quiet.sms(3000, "Ana Kamga", PHONE, null));
        f.matcher.matchManually(tenant, order, matched, seller);
        String text = quiet.sms(1500, "Ana Kamga", PHONE, null);
        payment(seller, text);
        UUID duplicate = payment(seller, text);
        UUID unparsed = payment(seller, "Hello, I paid you this morning, thanks");
        UUID aside = payment(seller, quiet.sms(1200, "Ana Kamga", PHONE, null));
        assertThat(f.matcher.markUnmatched(tenant, aside, seller).done()).isTrue();
        MatchWorld other = new MatchWorld();
        UUID sellerOfOther = MatchingFixture.user(other.tenant, "SELLER");
        UUID disabled = MatchingFixture.user(tenant, "SELLER");
        w.run("UPDATE app_user SET disabled_at = now() WHERE id = ?", disabled);
        String before = MatchInvariants.fingerprint(tenant);

        assertThat(f.matcher.markUnmatched(tenant, received, helper).reason()).as("a helper").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.markUnmatched(tenant, received, disabled).reason()).as("a disabled seller").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.markUnmatched(tenant, received, sellerOfOther).reason()).as("a seller of another tenant").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.markUnmatched(tenant, UUID.randomUUID(), seller).reason()).as("unknown").isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(f.matcher.markUnmatched(other.tenant, received, sellerOfOther).reason()).as("another tenant's payment").isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(f.matcher.markUnmatched(tenant, unverified, seller).reason()).as("unverified").isEqualTo(MatchingService.Reason.PAYMENT_STATUS_NOT_ALLOWED);
        assertThat(f.matcher.markUnmatched(tenant, matched, seller).reason()).as("matched").isEqualTo(MatchingService.Reason.PAYMENT_STATUS_NOT_ALLOWED);
        assertThat(f.matcher.markUnmatched(tenant, duplicate, seller).reason()).as("duplicate").isEqualTo(MatchingService.Reason.PAYMENT_STATUS_NOT_ALLOWED);
        assertThat(f.matcher.markUnmatched(tenant, unparsed, seller).reason()).as("unparsed").isEqualTo(MatchingService.Reason.PAYMENT_STATUS_NOT_ALLOWED);
        assertThat(f.matcher.markUnmatched(tenant, aside, seller).reason()).as("already aside").isEqualTo(MatchingService.Reason.PAYMENT_STATUS_NOT_ALLOWED);

        assertThat(f.matcher.reopen(tenant, aside, helper).reason()).as("a helper").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.reopen(tenant, aside, sellerOfOther).reason()).as("a seller of another tenant").isEqualTo(MatchingService.Reason.NOT_A_SELLER);
        assertThat(f.matcher.reopen(tenant, UUID.randomUUID(), seller).reason()).isEqualTo(MatchingService.Reason.NOT_FOUND);
        assertThat(f.matcher.reopen(tenant, received, seller).reason()).as("not set aside").isEqualTo(MatchingService.Reason.PAYMENT_STATUS_NOT_ALLOWED);
        assertThat(f.matcher.reopen(tenant, matched, seller).reason()).isEqualTo(MatchingService.Reason.PAYMENT_STATUS_NOT_ALLOWED);
        assertThat(f.matcher.reopen(tenant, unverified, seller).reason()).isEqualTo(MatchingService.Reason.PAYMENT_STATUS_NOT_ALLOWED);
        assertThat(MatchInvariants.fingerprint(tenant)).as("nothing changed").isEqualTo(before);
        assertThat(count("SELECT count(*) FROM payment WHERE tenant_id = ? AND status = 'UNMATCHED'", tenant, null)).isEqualTo(1);
    }

    @Test
    void setting_aside_taking_back_and_rematching_are_logged_with_ids_and_codes_only() {
        order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(seller, quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        try (LogCapture logs = new LogCapture()) {
            f.matcher.markUnmatched(tenant, payment, helper);
            f.matcher.markUnmatched(tenant, payment, seller);
            f.matcher.reopen(tenant, payment, seller);
            f.matcher.rematch(tenant, helper);
            f.matcher.rematch(tenant, seller);
            String log = logs.text();
            assertThat(log).contains("seller action refused", "action=PAYMENT_UNMATCHED", "action=REMATCH", "NOT_A_SELLER", "payment status set by the seller",
                    "status=UNMATCHED", "status=RECEIVED", "rematch finished", "examined=1", "applied=1", payment.toString());
            assertThat(log).doesNotContain("Ana").doesNotContain("Kamga").doesNotContain("A-1042").doesNotContain("999 000");
        }
    }

    @Test
    void a_seller_action_on_a_payment_needs_its_arguments() {
        assertThatNullPointerException().isThrownBy(() -> f.matcher.markUnmatched(tenant, null, seller)).withMessage("paymentId");
        assertThatNullPointerException().isThrownBy(() -> f.matcher.markUnmatched(tenant, UUID.randomUUID(), null)).withMessage("actor");
        assertThatNullPointerException().isThrownBy(() -> f.matcher.reopen(tenant, null, seller)).withMessage("paymentId");
        assertThatNullPointerException().isThrownBy(() -> f.matcher.unmatch(tenant, null, seller)).withMessage("matchId");
        assertThatNullPointerException().isThrownBy(() -> f.matcher.unmatch(tenant, UUID.randomUUID(), null)).withMessage("actor");
        assertThatNullPointerException().isThrownBy(() -> f.matcher.rematch(tenant, null)).withMessage("actor");
    }

    // ── Rematch ────────────────────────────────────────────────────────────

    @Test
    void a_rematch_picks_up_what_the_trigger_missed_records_who_asked_and_changes_nothing_when_repeated() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(seller, quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        UUID helped = payment(helper, quiet.sms(900, "Bea Nkomo", "+999 000 000 5678", null));
        UUID order2 = order("B-2", "Bea Nkomo", "+999 000 000 5678", 900);
        assertThat(MatchingFixture.status("orders", order)).as("the trigger was off").isEqualTo("PENDING_PAYMENT");

        MatchingService.RematchResult r = f.matcher.rematch(tenant, seller);

        assertThat(r.refusal()).isNull();
        assertThat(r.examined()).isEqualTo(2);
        assertThat(r.applied()).isEqualTo(1);
        assertThat(r.suggested()).isEqualTo(1);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("MATCHED");
        assertThat(f.repository.forPayment(tenant, helped)).extracting(MatchRepository.Stored::state).containsExactly("SUGGESTED");
        assertThat(MatchingFixture.status("orders", order2)).isEqualTo("PENDING_PAYMENT");
        assertThat(audit("REMATCH", seller)).isEqualTo(1);
        assertThat(MatchingFixture.rows("SELECT subject FROM audit_log WHERE tenant_id = ? AND action = 'REMATCH'", tenant)).containsExactly(tenant.toString());
        MatchInvariants.assertHold(tenant);

        String after = MatchInvariants.fingerprint(tenant);
        MatchingService.RematchResult again = f.matcher.rematch(tenant, seller);
        assertThat(again.applied()).isZero();
        assertThat(again.suggested()).isZero();
        assertThat(again.examined()).isEqualTo(1);
        assertThat(MatchInvariants.fingerprint(tenant).replaceAll("audit=\\d+", "")).isEqualTo(after.replaceAll("audit=\\d+", ""));
    }

    @Test
    void a_rematch_is_for_the_seller_only() {
        order("A-1042", "Ana Kamga", PHONE, 5000);
        payment(seller, quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        MatchWorld other = new MatchWorld();
        UUID sellerOfOther = MatchingFixture.user(other.tenant, "SELLER");
        UUID disabled = MatchingFixture.user(tenant, "SELLER");
        w.run("UPDATE app_user SET disabled_at = now() WHERE id = ?", disabled);
        String before = MatchInvariants.fingerprint(tenant);
        for (UUID who : new UUID[]{helper, disabled, sellerOfOther, UUID.randomUUID()}) {
            MatchingService.RematchResult r = f.matcher.rematch(tenant, who);
            assertThat(r.refusal()).isEqualTo(MatchingService.Reason.NOT_A_SELLER);
            assertThat(r.examined()).isZero();
            assertThat(r.applied()).isZero();
        }
        assertThat(MatchInvariants.fingerprint(tenant)).as("nothing ran, nothing was audited").isEqualTo(before);
    }

    @Test
    void a_rematch_obeys_the_kill_switch_and_the_candidate_cap() {
        MatchingFixture off = new MatchingFixture(new MatchConfig(85, 60, false, 200, 10), MatchingService.StepHook.NONE, false);
        order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = payment(seller, quiet.sms(5000, "Ana Kamga", PHONE, "A-1042"));
        MatchingService.RematchResult r = off.matcher.rematch(tenant, seller);
        assertThat(r.applied()).as("a perfect pair, but the switch is off").isZero();
        assertThat(r.suggested()).isEqualTo(1);
        assertThat(f.repository.forPayment(tenant, payment)).extracting(MatchRepository.Stored::state).containsExactly("SUGGESTED");

        MatchWorld crowd = new MatchWorld();
        UUID crowdSeller = MatchingFixture.user(crowd.tenant, "SELLER");
        for (int i = 0; i < 12; i++) {
            crowd.payment(1000 + i, "RECEIVED");
        }
        MatchingFixture small = new MatchingFixture(new MatchConfig(85, 60, true, 10, 10), MatchingService.StepHook.NONE, false);
        assertThat(small.matcher.rematch(crowd.tenant, crowdSeller).examined()).as("the ten most recent").isEqualTo(10);
    }

    @Test
    void a_failure_on_one_payment_is_logged_by_id_and_does_not_stop_the_others() {
        order("A-1", "Ana Kamga", PHONE, 5000);
        order("B-2", "Bea Nkomo", "+999 000 000 5678", 4000);
        UUID broken = payment(seller, quiet.sms(5000, "Ana Kamga", PHONE, "A-1"));
        UUID fine = payment(seller, quiet.sms(4000, "Bea Nkomo", "+999 000 000 5678", "B-2"));
        MatchRepository flaky = new MatchRepository(MatchWorld.DB.appDataSource()) {
            @Override
            public java.util.Optional<PaymentCandidate> paymentCandidate(UUID tenantId, UUID paymentId) {
                if (paymentId.equals(broken)) {
                    throw new IllegalStateException("injected, with a secret name Ana Kamga");
                }
                return super.paymentCandidate(tenantId, paymentId);
            }
        };
        MatchingService service = new MatchingService(MatchWorld.DB.appDataSource(), flaky, MatchConfig.defaults(), new MatchEvents(), f.clock);
        try (LogCapture logs = new LogCapture()) {
            MatchingService.RematchResult r = service.rematch(tenant, seller);
            assertThat(r.examined()).isEqualTo(2);
            assertThat(r.applied()).isEqualTo(1);
            assertThat(logs.text()).contains("matching failed", broken.toString()).doesNotContain("Kamga").doesNotContain("injected");
        }
        assertThat(MatchingFixture.status("payment", fine)).isEqualTo("MATCHED");
        assertThat(MatchingFixture.status("payment", broken)).isEqualTo("RECEIVED");
    }
}
