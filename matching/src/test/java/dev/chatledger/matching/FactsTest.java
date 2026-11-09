package dev.chatledger.matching;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bounds of the facts the scorer is given and of a scored pair: a value out of range is refused, not scored. Task 6.1. */
class FactsTest {

    private static final Instant T = Instant.parse("2026-11-03T10:00:00Z");
    private static final long MAX = 1_000_000_000_000L;

    private static OrderFacts order(long total, long applied) {
        return new OrderFacts(UUID.randomUUID(), total, applied, "XAF", T, null, null, null, null, false, false);
    }

    private static PaymentFacts payment(long amount) {
        return new PaymentFacts(UUID.randomUUID(), amount, "XAF", true, null, null, null, null, null, false);
    }

    @Test
    void an_order_total_is_between_1_and_the_cap() {
        assertThatCode(() -> order(1, 0)).doesNotThrowAnyException();
        assertThatCode(() -> order(MAX, 0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> order(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order(MAX + 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_applied_amount_is_between_zero_and_the_total() {
        assertThatCode(() -> order(100, 0)).doesNotThrowAnyException();
        assertThatCode(() -> order(100, 100)).doesNotThrowAnyException();
        assertThatThrownBy(() -> order(100, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order(100, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(order(100, 30).balanceMinor()).isEqualTo(70);
    }

    @Test
    void a_payment_amount_is_between_1_and_the_cap() {
        assertThatCode(() -> payment(1)).doesNotThrowAnyException();
        assertThatCode(() -> payment(MAX)).doesNotThrowAnyException();
        assertThatThrownBy(() -> payment(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payment(MAX + 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ids_and_currencies_are_required() {
        assertThatThrownBy(() -> new OrderFacts(null, 1, 0, "XAF", T, null, null, null, null, false, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderFacts(UUID.randomUUID(), 1, 0, null, T, null, null, null, null, false, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderFacts(UUID.randomUUID(), 1, 0, "XAF", null, null, null, null, null, false, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentFacts(null, 1, "XAF", true, null, null, null, null, null, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentFacts(UUID.randomUUID(), 1, null, true, null, null, null, null, null, false)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_scored_pair_has_a_score_from_0_to_100_and_keeps_private_copies() {
        UUID id = UUID.randomUUID();
        assertThatCode(() -> new ScoredPair(id, id, 0, List.of(), java.util.Set.of(), false)).doesNotThrowAnyException();
        assertThatCode(() -> new ScoredPair(id, id, 100, List.of(), java.util.Set.of(), false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new ScoredPair(id, id, -1, List.of(), java.util.Set.of(), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoredPair(id, id, 101, List.of(), java.util.Set.of(), false)).isInstanceOf(IllegalArgumentException.class);
        List<Reason> reasons = new java.util.ArrayList<>(List.of(new Reason(ReasonCode.AMOUNT_EXACT, 50)));
        ScoredPair pair = new ScoredPair(id, id, 50, reasons, java.util.Set.of(), false);
        reasons.clear();
        assertThat(pair.reasons()).hasSize(1);
        java.util.Set<FloorCode> floors = java.util.EnumSet.of(FloorCode.AMOUNT_EXACT);
        ScoredPair withFloors = new ScoredPair(id, id, 50, List.of(), floors, false);
        floors.clear();
        assertThat(withFloors.floorsMet()).containsExactly(FloorCode.AMOUNT_EXACT);
    }

    @Test
    void only_a_seller_or_the_machine_feed_is_trusted() {
        assertThat(Recorder.SELLER.trusted()).isTrue();
        assertThat(Recorder.MACHINE.trusted()).isTrue();
        assertThat(Recorder.HELPER.trusted()).isFalse();
    }
}
