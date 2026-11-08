package dev.chatledger.matching;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every signal, tier and floor of the score, with the points and reason codes each produces. All names, numbers and hashes are invented.
 *
 * <p>Requirement: 3.1, 3.4. Task 6.1.
 */
class MatchScorerTest {

    private static final Instant ORDERED = Instant.parse("2026-11-03T10:00:00Z");
    private static final MatchConfig CONFIG = MatchConfig.defaults();
    private static final String HASH = "ab".repeat(32);

    private static OrderFacts order(long total, long applied, String ref, String name, String hash, String last4, boolean shared) {
        return new OrderFacts(UUID.randomUUID(), total, applied, "XAF", ORDERED, ref, name, hash, last4, shared);
    }

    private static OrderFacts order(String ref, String name, String hash) {
        return order(5000, 0, ref, name, hash, "1234", false);
    }

    private static PaymentFacts payment(long amount, String ref, String name, String hash, String last4, Instant paidAt, boolean tx) {
        return new PaymentFacts(UUID.randomUUID(), amount, "XAF", tx, ref, name, hash, last4, paidAt);
    }

    private static PaymentFacts payment(long amount) {
        return payment(amount, null, null, null, null, null, true);
    }

    private static ScoredPair score(OrderFacts o, PaymentFacts p) {
        return MatchScorer.score(o, p, Recorder.SELLER, CONFIG);
    }

    private static List<ReasonCode> codes(ScoredPair s) {
        return s.reasons().stream().map(Reason::code).toList();
    }

    // ── Amount ─────────────────────────────────────────────────────────────

    @Test
    void an_exact_amount_scores_50() {
        ScoredPair s = score(order(null, null, null), payment(5000));
        assertThat(s.score()).isEqualTo(50);
        assertThat(codes(s)).containsExactly(ReasonCode.AMOUNT_EXACT);
        assertThat(s.floorsMet()).contains(FloorCode.AMOUNT_EXACT);
    }

    @Test
    void the_balance_is_the_total_minus_what_is_already_applied() {
        OrderFacts partlyPaid = order(5000, 2000, null, null, null, null, false);
        assertThat(score(partlyPaid, payment(3000)).score()).isEqualTo(50);
        assertThat(codes(score(partlyPaid, payment(5000)))).containsExactly(ReasonCode.AMOUNT_OVER);
        assertThat(codes(score(partlyPaid, payment(2999)))).containsExactly(ReasonCode.AMOUNT_PARTIAL);
    }

    @Test
    void a_partial_or_an_over_payment_scores_20_and_is_never_exact() {
        ScoredPair partial = score(order(null, null, null), payment(1));
        assertThat(partial.score()).isEqualTo(20);
        assertThat(codes(partial)).containsExactly(ReasonCode.AMOUNT_PARTIAL);
        assertThat(partial.floorsMet()).doesNotContain(FloorCode.AMOUNT_EXACT);
        ScoredPair over = score(order(null, null, null), payment(5001));
        assertThat(over.score()).isEqualTo(20);
        assertThat(codes(over)).containsExactly(ReasonCode.AMOUNT_OVER);
        assertThat(over.floorsMet()).doesNotContain(FloorCode.AMOUNT_EXACT);
    }

    @Test
    void an_over_payment_counts_only_up_to_twice_the_balance() {
        assertThat(score(order(null, null, null), payment(10_000)).score()).isEqualTo(20);
        assertThat(score(order(null, null, null), payment(10_001)).score()).isEqualTo(0);
    }

    @Test
    void an_order_with_nothing_left_to_pay_and_a_different_currency_are_not_candidates() {
        OrderFacts settled = order(5000, 5000, "A-1", "Ana Kamga", HASH, "1234", false);
        PaymentFacts everythingElseAgrees = payment(5000, "A-1", "Ana Kamga", HASH, "1234", ORDERED, true);
        ScoredPair none = score(settled, everythingElseAgrees);
        assertThat(none.score()).as("every other signal agrees, but nothing is left to pay").isZero();
        assertThat(none.reasons()).isEmpty();
        assertThat(none.autoEligible()).isFalse();
        PaymentFacts euro = new PaymentFacts(UUID.randomUUID(), 5000, "EUR", true, null, null, null, null, null);
        ScoredPair s = score(order(null, null, null), euro);
        assertThat(s.score()).isZero();
        assertThat(s.reasons()).isEmpty();
        assertThat(s.autoEligible()).isFalse();
    }

    // ── Reference ──────────────────────────────────────────────────────────

    @Test
    void a_matching_reference_scores_25_whatever_its_case() {
        ScoredPair s = score(order("A-1042", null, null), payment(1, "a-1042", null, null, null, null, true));
        assertThat(codes(s)).contains(ReasonCode.REFERENCE_MATCH);
        assertThat(s.score()).isEqualTo(20 + 25);
        assertThat(s.floorsMet()).contains(FloorCode.REFERENCE_UNIQUE_MATCH);
    }

    @Test
    void a_reference_shared_by_two_open_orders_proves_nothing() {
        OrderFacts shared = order(5000, 0, "A-1042", null, null, null, true);
        ScoredPair s = score(shared, payment(5000, "A-1042", null, null, null, null, true));
        assertThat(codes(s)).containsExactly(ReasonCode.AMOUNT_EXACT, ReasonCode.REFERENCE_AMBIGUOUS);
        assertThat(s.score()).isEqualTo(50);
        assertThat(s.floorsMet()).doesNotContain(FloorCode.REFERENCE_UNIQUE_MATCH);
    }

    @Test
    void a_different_or_missing_reference_scores_nothing() {
        assertThat(score(order("A-1042", null, null), payment(1, "A-1043", null, null, null, null, true)).score()).isEqualTo(20);
        assertThat(score(order(null, null, null), payment(1, "A-1042", null, null, null, null, true)).score()).isEqualTo(20);
        assertThat(score(order("A-1042", null, null), payment(1)).score()).isEqualTo(20);
    }

    // ── Identity ───────────────────────────────────────────────────────────

    @Test
    void an_equal_phone_hash_scores_15_and_beats_the_name() {
        ScoredPair s = score(order(null, "Bea Nkomo", HASH), payment(1, null, "Ana Kamga", HASH, "1234", null, true));
        assertThat(codes(s)).containsExactly(ReasonCode.AMOUNT_PARTIAL, ReasonCode.IDENTITY_PHONE);
        assertThat(s.score()).isEqualTo(20 + 15);
        assertThat(s.floorsMet()).contains(FloorCode.IDENTITY_PRESENT);
    }

    @Test
    void a_different_hash_is_not_identity_and_does_not_stop_the_name_from_counting() {
        ScoredPair s = score(order(null, "Ana Kamga", HASH), payment(1, null, "Ana Kamga", "cd".repeat(32), null, null, true));
        assertThat(codes(s)).containsExactly(ReasonCode.AMOUNT_PARTIAL, ReasonCode.IDENTITY_NAME_FULL);
        assertThat(s.score()).isEqualTo(20 + 12);
    }

    @Test
    void a_full_name_scores_12_an_initial_8_and_the_last_four_adds_3_only_beside_a_name() {
        OrderFacts customer = order(5000, 0, null, "Ana Kamga", null, "1234", false);
        assertThat(score(customer, payment(1, null, "Kamga Ana", null, null, null, true)).score()).isEqualTo(20 + 12);
        assertThat(score(customer, payment(1, null, "Ana K.", null, null, null, true)).score()).isEqualTo(20 + 8);
        ScoredPair withLast4 = score(customer, payment(1, null, "Ana Kamga", null, "1234", null, true));
        assertThat(withLast4.score()).isEqualTo(20 + 12 + 3);
        assertThat(codes(withLast4)).contains(ReasonCode.IDENTITY_LAST4);
        ScoredPair last4Alone = score(customer, payment(1, null, "Bea Nkomo", null, "1234", null, true));
        assertThat(last4Alone.score()).as("four digits alone are not identity").isEqualTo(20);
        assertThat(last4Alone.floorsMet()).doesNotContain(FloorCode.IDENTITY_PRESENT);
        assertThat(score(customer, payment(1, null, "Ana Kamga", null, "9999", null, true)).score()).isEqualTo(20 + 12);
    }

    @Test
    void the_neutral_customer_name_never_scores() {
        ScoredPair s = score(order(5000, 0, null, NameSimilarity.NEUTRAL_NAME, null, null, false), payment(1, null, "Unknown customer", null, null, null, true));
        assertThat(s.score()).isEqualTo(20);
    }

    // ── Time ───────────────────────────────────────────────────────────────

    @Test
    void the_window_runs_from_ten_minutes_before_the_order_to_seven_days_after() {
        OrderFacts o = order(null, null, null);
        assertThat(score(o, payment(1, null, null, null, null, ORDERED, true)).score()).isEqualTo(30);
        assertThat(score(o, payment(1, null, null, null, null, ORDERED.minus(Duration.ofMinutes(10)), true)).score()).isEqualTo(30);
        assertThat(score(o, payment(1, null, null, null, null, ORDERED.minus(Duration.ofMinutes(10)).minusNanos(1), true)).score()).isEqualTo(20);
        assertThat(score(o, payment(1, null, null, null, null, ORDERED.plus(Duration.ofDays(7)), true)).score()).isEqualTo(30);
        assertThat(score(o, payment(1, null, null, null, null, ORDERED.plus(Duration.ofDays(7)).plusNanos(1), true)).score()).isEqualTo(20);
        assertThat(codes(score(o, payment(1, null, null, null, null, ORDERED, true)))).contains(ReasonCode.TIME_IN_WINDOW);
    }

    // ── Totals, caps and floors ────────────────────────────────────────────

    @Test
    void every_signal_together_is_100() {
        OrderFacts o = order(5000, 0, "A-1042", "Ana Kamga", HASH, "1234", false);
        PaymentFacts p = payment(5000, "A-1042", "Ana Kamga", HASH, "1234", ORDERED.plusSeconds(60), true);
        ScoredPair s = score(o, p);
        assertThat(s.score()).isEqualTo(100);
        assertThat(s.reasons().stream().mapToInt(Reason::points).sum()).isEqualTo(100);
        assertThat(s.autoEligible()).isTrue();
        assertThat(s.floorsMet()).containsExactlyInAnyOrder(FloorCode.values());
    }

    @Test
    void the_weights_add_up_to_100() {
        assertThat(MatchScorer.AMOUNT_EXACT_POINTS + MatchScorer.REFERENCE_POINTS + MatchScorer.IDENTITY_PHONE_POINTS
                + MatchScorer.TIME_POINTS).isEqualTo(100);
        assertThat(MatchScorer.IDENTITY_NAME_FULL_POINTS + MatchScorer.IDENTITY_LAST4_POINTS).isEqualTo(MatchScorer.IDENTITY_PHONE_POINTS);
    }

    @Test
    void a_payment_without_a_transaction_id_is_held_below_the_automatic_threshold() {
        OrderFacts o = order(5000, 0, "A-1042", "Ana Kamga", HASH, "1234", false);
        ScoredPair s = score(o, payment(5000, "A-1042", "Ana Kamga", HASH, "1234", ORDERED, false));
        assertThat(s.score()).isEqualTo(84);
        assertThat(codes(s)).contains(ReasonCode.UNVERIFIED_CAPPED);
        assertThat(s.autoEligible()).isFalse();
        assertThat(s.floorsMet()).doesNotContain(FloorCode.HAS_TRANSACTION_ID);
        ScoredPair low = score(order(null, null, null), payment(5000, null, null, null, null, null, false));
        assertThat(low.score()).isEqualTo(50);
        assertThat(codes(low)).as("nothing to cap").doesNotContain(ReasonCode.UNVERIFIED_CAPPED);
    }

    @Test
    void the_lowest_automatic_scores_each_need_every_floor() {
        // exact + reference + a full name = 87, + phone = 90, exact + reference + initial + time = 93
        assertThat(score(order("A-1", "Ana Kamga", null), payment(5000, "A-1", "Ana Kamga", null, null, null, true)).score()).isEqualTo(87);
        assertThat(score(order("A-1", null, HASH), payment(5000, "A-1", null, HASH, null, null, true)).score()).isEqualTo(90);
        assertThat(score(order("A-1", "Ana Kamga", null), payment(5000, "A-1", "Ana K.", null, null, ORDERED, true)).score()).isEqualTo(93);
        assertThat(score(order("A-1", "Ana Kamga", null), payment(5000, "A-1", "Ana K.", null, null, ORDERED, true)).autoEligible()).isTrue();
    }

    @Test
    void exact_plus_reference_plus_time_is_85_but_not_automatic_without_an_identity() {
        ScoredPair s = score(order("A-1", null, null), payment(5000, "A-1", null, null, null, ORDERED, true));
        assertThat(s.score()).isEqualTo(85);
        assertThat(s.floorsMet()).doesNotContain(FloorCode.IDENTITY_PRESENT);
        assertThat(s.autoEligible()).isFalse();
    }

    @Test
    void each_missing_floor_alone_makes_a_high_score_not_automatic() {
        OrderFacts o = order(5000, 0, "A-1042", "Ana Kamga", HASH, "1234", false);
        PaymentFacts good = payment(5000, "A-1042", "Ana Kamga", HASH, "1234", ORDERED, true);
        assertThat(score(o, good).autoEligible()).isTrue();
        // a helper recorded it
        ScoredPair helper = MatchScorer.score(o, good, Recorder.HELPER, CONFIG);
        assertThat(helper.score()).isEqualTo(100);
        assertThat(helper.floorsMet()).doesNotContain(FloorCode.TRUSTED_RECORDER);
        assertThat(helper.autoEligible()).isFalse();
        assertThat(MatchScorer.score(o, good, Recorder.MACHINE, CONFIG).autoEligible()).isTrue();
        // the kill switch
        MatchConfig off = new MatchConfig(85, 60, false, 200, 10);
        ScoredPair killed = MatchScorer.score(o, good, Recorder.SELLER, off);
        assertThat(killed.score()).isEqualTo(100);
        assertThat(killed.floorsMet()).doesNotContain(FloorCode.AUTO_ENABLED);
        assertThat(killed.autoEligible()).isFalse();
        // reference shared, partial, no transaction id
        assertThat(score(order(5000, 0, "A-1042", "Ana Kamga", HASH, "1234", true), good).autoEligible()).isFalse();
        assertThat(score(o, payment(4999, "A-1042", "Ana Kamga", HASH, "1234", ORDERED, true)).autoEligible()).isFalse();
        assertThat(score(o, payment(5001, "A-1042", "Ana Kamga", HASH, "1234", ORDERED, true)).autoEligible()).isFalse();
        assertThat(score(o, payment(5000, "A-1042", "Ana Kamga", HASH, "1234", ORDERED, false)).autoEligible()).isFalse();
    }

    @Test
    void the_score_is_automatic_only_at_or_above_the_threshold() {
        OrderFacts o = order("A-1", "Ana Kamga", null);
        PaymentFacts p = payment(5000, "A-1", "Ana Kamga", null, null, null, true);   // 87
        assertThat(MatchScorer.score(o, p, Recorder.SELLER, new MatchConfig(87, 60, true, 200, 10)).autoEligible()).isTrue();
        assertThat(MatchScorer.score(o, p, Recorder.SELLER, new MatchConfig(88, 60, true, 200, 10)).autoEligible()).isFalse();
    }

    @Test
    void lowering_the_threshold_to_its_bound_still_needs_a_reference_a_transaction_id_and_an_exact_amount() {
        MatchConfig lowest = new MatchConfig(75, 40, true, 200, 10);
        OrderFacts o = order("A-1", "Ana Kamga", HASH);
        // identity and time and exact amount = 75, but no reference
        ScoredPair noReference = MatchScorer.score(o, payment(5000, null, "Ana Kamga", HASH, "1234", ORDERED, true), Recorder.SELLER, lowest);
        assertThat(noReference.score()).isEqualTo(75);
        assertThat(noReference.autoEligible()).isFalse();
        // reference, identity, time, a partial amount = 70 (and below 75 anyway); over-payment likewise
        ScoredPair partial = MatchScorer.score(o, payment(4000, "A-1", "Ana Kamga", HASH, "1234", ORDERED, true), Recorder.SELLER, lowest);
        assertThat(partial.score()).isEqualTo(70);
        assertThat(partial.autoEligible()).isFalse();
    }

    @Test
    void results_and_facts_print_only_ids_and_reasons_hold_only_codes() {
        OrderFacts o = order("SECRET-REF", "Secret Name", HASH);
        PaymentFacts p = payment(5000, "SECRET-REF", "Secret Name", HASH, "1234", ORDERED, true);
        assertThat(o.toString()).doesNotContain("SECRET").doesNotContain("Secret").doesNotContain(HASH);
        assertThat(p.toString()).doesNotContain("SECRET").doesNotContain("Secret").doesNotContain(HASH);
        ScoredPair s = score(o, p);
        assertThat(s.reasonsJson()).doesNotContain("SECRET").doesNotContain("Secret").doesNotContain(HASH);
    }
}
