package dev.chatledger.suspicion.rules;

import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.PaymentContext;
import dev.chatledger.suspicion.SenderRecord;
import dev.chatledger.suspicion.SubjectType;
import dev.chatledger.suspicion.SuspicionConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static dev.chatledger.suspicion.rules.RuleTables.assertFlag;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payment rules R1, R2, R6, R9 and R10 (payment side), each with the example table of the design stop: positive and negative rows and
 * the boundaries. Synthetic names only.
 *
 * <p>Requirement: 5. Task 7.
 */
class PaymentRulesTest {
    private static final SuspicionConfig C = SuspicionConfig.defaults();
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final String HASH = "cc".repeat(32);

    private static PaymentContext ctx(String status, Instant paidAt, String hash, String name, List<SenderRecord> earlier) {
        return new PaymentContext(UUID.randomUUID(), status, paidAt, NOW, hash, name, earlier);
    }

    private static PaymentContext ctx(String status) {
        return ctx(status, NOW.minusSeconds(60), HASH, "Ana Kamga", List.of());
    }

    // ── R1 ─────────────────────────────────────────────────────────────────
    @Test
    void r1_a_duplicate_payment_is_flagged_and_nothing_else_is() {
        DuplicateTransactionRule r = new DuplicateTransactionRule();
        PaymentContext dup = ctx("DUPLICATE");
        Optional<Finding> f = r.evaluate(dup, C);
        assertFlag(f, true, FlagCode.DUPLICATE_TX, "DUPLICATE");
        assertThat(f.get()).isEqualTo(new Finding(FlagCode.DUPLICATE_TX, SubjectType.PAYMENT, dup.paymentId(), null));
        for (String other : List.of("RECEIVED", "UNVERIFIED", "UNPARSED", "MATCHED", "UNMATCHED", "duplicate")) {
            assertFlag(r.evaluate(ctx(other), C), false, FlagCode.DUPLICATE_TX, other);
        }
    }

    // ── R2 ─────────────────────────────────────────────────────────────────
    @Test
    void r2_a_payment_without_a_transaction_id_is_flagged() {
        NoProviderIdRule r = new NoProviderIdRule();
        assertFlag(r.evaluate(ctx("UNVERIFIED"), C), true, FlagCode.NO_PROVIDER_ID, "UNVERIFIED");
        for (String other : List.of("RECEIVED", "DUPLICATE", "UNPARSED", "unverified")) {
            assertFlag(r.evaluate(ctx(other), C), false, FlagCode.NO_PROVIDER_ID, other);
        }
    }

    // ── R6 ─────────────────────────────────────────────────────────────────
    private static List<SenderRecord> earlier(Instant paidAt, Duration... ago) {
        List<SenderRecord> out = new ArrayList<>();
        for (Duration d : ago) {
            out.add(new SenderRecord("Ana Kamga", paidAt.minus(d)));
        }
        return out;
    }

    @Test
    void r6_four_payments_from_one_phone_in_thirty_minutes_are_a_burst() {
        SenderBurstRule r = new SenderBurstRule();
        Instant t = NOW.minusSeconds(60);
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, "A", earlier(t, Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(20))), C),
                true, FlagCode.SENDER_BURST, "3 earlier in 20 min");
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, "A", earlier(t, Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(30))), C),
                true, FlagCode.SENDER_BURST, "the oldest exactly 30 min before");
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, "A", earlier(t, Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(31))), C),
                false, FlagCode.SENDER_BURST, "the oldest 31 min before");
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, "A", earlier(t, Duration.ofMinutes(1), Duration.ofMinutes(2))), C),
                false, FlagCode.SENDER_BURST, "2 earlier");
        assertFlag(r.evaluate(ctx("RECEIVED", t, null, "A", earlier(t, Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(3))), C),
                false, FlagCode.SENDER_BURST, "no sender phone");
        assertFlag(r.evaluate(ctx("RECEIVED", null, HASH, "A", earlier(NOW, Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(3))), C),
                false, FlagCode.SENDER_BURST, "no payment time");
        // A later payment (out of order) does not count toward a burst that ends at this one.
        List<SenderRecord> later = List.of(new SenderRecord("A", t.plusSeconds(60)), new SenderRecord("A", t.plusSeconds(120)), new SenderRecord("A", t.plusSeconds(180)));
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, "A", later), C), false, FlagCode.SENDER_BURST, "only later payments");
    }

    @Test
    void r6_the_count_comes_from_the_configuration() {
        SenderBurstRule r = new SenderBurstRule();
        Instant t = NOW.minusSeconds(60);
        SuspicionConfig two = new SuspicionConfig(1000, Duration.ofMinutes(60), 2, Duration.ofMinutes(30), Duration.ofDays(3), Duration.ofMinutes(2),
                Duration.ofDays(90), 1000, java.util.Set.of());
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, "A", earlier(t, Duration.ofMinutes(5))), two), true, FlagCode.SENDER_BURST, "2 with a count of 2");
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, "A", List.of()), two), false, FlagCode.SENDER_BURST, "1 with a count of 2");
    }

    // ── R9 ─────────────────────────────────────────────────────────────────
    @Test
    void r9_a_payment_time_far_from_the_recording_time_is_flagged() {
        UsualWindowRule r = new UsualWindowRule();
        Instant day7noon = NOW.minus(Duration.ofDays(3));
        record Row(Instant paidAt, boolean flag, String name) { }
        for (Row row : List.of(new Row(NOW.minus(Duration.ofDays(4)), true, "day 6"), new Row(day7noon.minusSeconds(60), true, "day 7, 11:59"),
                new Row(day7noon, false, "day 7, 12:00 (exactly the age)"), new Row(NOW.plusSeconds(60), false, "12:01"),
                new Row(NOW.plusSeconds(120), false, "12:02 (exactly ahead)"), new Row(NOW.plusSeconds(180), true, "12:03"),
                new Row(NOW, false, "now"))) {
            assertFlag(r.evaluate(ctx("RECEIVED", row.paidAt, HASH, "A", List.of()), C), row.flag, FlagCode.PAID_OUTSIDE_USUAL_WINDOW, row.name);
        }
        assertFlag(r.evaluate(ctx("RECEIVED", null, HASH, "A", List.of()), C), false, FlagCode.PAID_OUTSIDE_USUAL_WINDOW, "no time");
    }

    // ── R10, payment side ──────────────────────────────────────────────────
    @Test
    void r10_the_same_phone_under_another_name_within_the_lookback_is_flagged() {
        SenderAliasPaymentRule r = new SenderAliasPaymentRule();
        Instant t = NOW.minusSeconds(60);
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, "Ana Kamga", List.of(new SenderRecord("Bob Synthetic", t.minus(Duration.ofDays(10))))), C),
                true, FlagCode.SENDER_ALIAS, "another name 10 days ago");
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, "Ana Kamga", List.of(new SenderRecord("Bob Synthetic", t.minus(Duration.ofDays(90))))), C),
                true, FlagCode.SENDER_ALIAS, "exactly the lookback");
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, "Ana Kamga", List.of(new SenderRecord("Bob Synthetic", t.minus(Duration.ofDays(91))))), C),
                false, FlagCode.SENDER_ALIAS, "beyond the lookback");
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, "Ana Kamga", List.of(new SenderRecord("A. Kamga", t.minus(Duration.ofDays(1))))), C),
                false, FlagCode.SENDER_ALIAS, "an initial agrees");
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, "Al", List.of(new SenderRecord("AL", t.minus(Duration.ofDays(1))))), C),
                false, FlagCode.SENDER_ALIAS, "the same short name");
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, "Ana Kamga", List.of(new SenderRecord(null, t.minus(Duration.ofDays(1))))), C),
                false, FlagCode.SENDER_ALIAS, "an earlier payment without a name");
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, null, List.of(new SenderRecord("Bob Synthetic", t.minus(Duration.ofDays(1))))), C),
                false, FlagCode.SENDER_ALIAS, "this payment without a name");
        assertFlag(r.evaluate(ctx("RECEIVED", t, null, "Ana Kamga", List.of(new SenderRecord("Bob Synthetic", t.minus(Duration.ofDays(1))))), C),
                false, FlagCode.SENDER_ALIAS, "no sender phone");
        assertFlag(r.evaluate(ctx("RECEIVED", t, HASH, "Ana Kamga", List.of(new SenderRecord("Unknown customer", t.minus(Duration.ofDays(1))))), C),
                false, FlagCode.SENDER_ALIAS, "the neutral name is not a name");
    }
}
