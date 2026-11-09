package dev.chatledger.suspicion;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The runner, the codes, the findings and the configuration's bounds. Requirement 5. Task 7. */
class SuspicionTest {

    @Test
    void every_code_has_exactly_the_rules_of_the_design_and_a_sentence() {
        Set<FlagCode> covered = new HashSet<>();
        Stream.of(Suspicion.PAYMENT_RULES, Suspicion.ORDER_RULES, Suspicion.PAIR_RULES).flatMap(List::stream).forEach(r -> covered.add(r.code()));
        assertThat(covered).containsExactlyInAnyOrder(FlagCode.values());
        assertThat(FlagCode.values()).hasSize(10);
        assertThat(Suspicion.PAYMENT_RULES).extracting(Rule::code).containsExactly(FlagCode.DUPLICATE_TX, FlagCode.NO_PROVIDER_ID, FlagCode.SENDER_BURST,
                FlagCode.PAID_OUTSIDE_USUAL_WINDOW, FlagCode.SENDER_ALIAS);
        assertThat(Suspicion.ORDER_RULES).extracting(Rule::code).containsExactly(FlagCode.SENDER_ALIAS);
        assertThat(Suspicion.PAIR_RULES).extracting(Rule::code).containsExactly(FlagCode.AMOUNT_NEAR_MISS, FlagCode.PAID_BEFORE_ORDER,
                FlagCode.PHONE_MISMATCH, FlagCode.NAME_MISMATCH, FlagCode.OVERPAYMENT);
        for (FlagCode c : FlagCode.values()) {
            assertThat(c.sentence()).as(c.name()).isNotBlank().hasSizeLessThanOrEqualTo(300).doesNotContain("{").doesNotContain("<");
        }
    }

    @Test
    void the_runner_runs_every_enabled_rule_and_none_of_the_disabled_ones() {
        Instant now = Instant.parse("2026-11-10T12:00:00Z");
        PaymentContext p = new PaymentContext(UUID.randomUUID(), "UNVERIFIED", now.minus(Duration.ofDays(10)), now, null, null, List.of());
        assertThat(new Suspicion(SuspicionConfig.defaults()).payment(p)).extracting(Finding::code)
                .containsExactly(FlagCode.NO_PROVIDER_ID, FlagCode.PAID_OUTSIDE_USUAL_WINDOW);
        SuspicionConfig off = new SuspicionConfig(1000, Duration.ofMinutes(60), 4, Duration.ofMinutes(30), Duration.ofDays(3), Duration.ofMinutes(2),
                Duration.ofDays(90), 1000, EnumSet.of(FlagCode.NO_PROVIDER_ID));
        assertThat(new Suspicion(off).payment(p)).extracting(Finding::code).containsExactly(FlagCode.PAID_OUTSIDE_USUAL_WINDOW);
        var pair = Pairs.pair(Pairs.order(50000, "Ana Kamga", Pairs.HASH_A, "1234"), Pairs.payment(5000, "Cy Synthetic", Pairs.HASH_B, "5678",
                Pairs.ORDERED.minus(Duration.ofHours(3))));
        assertThat(new Suspicion(SuspicionConfig.defaults()).pair(pair)).extracting(Finding::code)
                .containsExactly(FlagCode.AMOUNT_NEAR_MISS, FlagCode.PAID_BEFORE_ORDER, FlagCode.PHONE_MISMATCH, FlagCode.NAME_MISMATCH);
        assertThat(new Suspicion(SuspicionConfig.defaults()).order(new OrderContext(UUID.randomUUID(), "Bob Synthetic", "Ana Kamga")))
                .extracting(Finding::code).containsExactly(FlagCode.SENDER_ALIAS);
    }

    @Test
    void the_defaults_enable_every_rule_and_are_the_approved_values() {
        SuspicionConfig d = SuspicionConfig.defaults();
        for (FlagCode c : FlagCode.values()) {
            assertThat(d.enabled(c)).as(c.name()).isTrue();
        }
        assertThat(d).isEqualTo(new SuspicionConfig(1000, Duration.ofMinutes(60), 4, Duration.ofMinutes(30), Duration.ofDays(3), Duration.ofMinutes(2),
                Duration.ofDays(90), 1000, Set.of()));
    }

    private static SuspicionConfig with(long nearMiss, Duration before, int count, Duration window, Duration age, Duration ahead, Duration lookback, int bp) {
        return new SuspicionConfig(nearMiss, before, count, window, age, ahead, lookback, bp, Set.of());
    }

    @Test
    void every_bound_is_inclusive_and_one_step_outside_stops_startup() {
        Duration m60 = Duration.ofMinutes(60), m30 = Duration.ofMinutes(30), d3 = Duration.ofDays(3), m2 = Duration.ofMinutes(2), d90 = Duration.ofDays(90);
        // inclusive limits are accepted
        with(1, m60, 4, m30, d3, m2, d90, 1000);
        with(SuspicionConfig.MAX_MINOR, m60, 4, m30, d3, m2, d90, 1000);
        with(1000, Duration.ofMinutes(10), 4, m30, d3, m2, d90, 1000);
        with(1000, Duration.ofHours(24), 4, m30, d3, m2, d90, 1000);
        with(1000, m60, 2, m30, d3, m2, d90, 1000);
        with(1000, m60, 20, m30, d3, m2, d90, 1000);
        with(1000, m60, 4, Duration.ofMinutes(5), d3, m2, d90, 1000);
        with(1000, m60, 4, Duration.ofHours(24), d3, m2, d90, 1000);
        with(1000, m60, 4, m30, Duration.ofDays(1), m2, d90, 1000);
        with(1000, m60, 4, m30, Duration.ofDays(34), m2, d90, 1000);
        with(1000, m60, 4, m30, d3, Duration.ZERO, d90, 1000);
        with(1000, m60, 4, m30, d3, Duration.ofMinutes(15), d90, 1000);
        with(1000, m60, 4, m30, d3, m2, Duration.ofDays(1), 1000);
        with(1000, m60, 4, m30, d3, m2, Duration.ofDays(365), 1000);
        with(1000, m60, 4, m30, d3, m2, d90, 100);
        with(1000, m60, 4, m30, d3, m2, d90, 10_000);
        // one step outside each is refused, with the key's name
        record Bad(String key, Runnable make) { }
        Duration s = Duration.ofSeconds(1);
        for (Bad bad : List.of(
                new Bad("near-miss-min", () -> with(0, m60, 4, m30, d3, m2, d90, 1000)),
                new Bad("near-miss-min", () -> with(SuspicionConfig.MAX_MINOR + 1, m60, 4, m30, d3, m2, d90, 1000)),
                new Bad("before-order-tolerance", () -> with(1000, Duration.ofMinutes(10).minus(s), 4, m30, d3, m2, d90, 1000)),
                new Bad("before-order-tolerance", () -> with(1000, Duration.ofHours(24).plus(s), 4, m30, d3, m2, d90, 1000)),
                new Bad("before-order-tolerance", () -> with(1000, null, 4, m30, d3, m2, d90, 1000)),
                new Bad("burst-count", () -> with(1000, m60, 1, m30, d3, m2, d90, 1000)),
                new Bad("burst-count", () -> with(1000, m60, 21, m30, d3, m2, d90, 1000)),
                new Bad("burst-window", () -> with(1000, m60, 4, Duration.ofMinutes(5).minus(s), d3, m2, d90, 1000)),
                new Bad("burst-window", () -> with(1000, m60, 4, Duration.ofHours(24).plus(s), d3, m2, d90, 1000)),
                new Bad("usual-age", () -> with(1000, m60, 4, m30, Duration.ofDays(1).minus(s), m2, d90, 1000)),
                new Bad("usual-age", () -> with(1000, m60, 4, m30, Duration.ofDays(34).plus(s), m2, d90, 1000)),
                new Bad("usual-ahead", () -> with(1000, m60, 4, m30, d3, s.negated(), d90, 1000)),
                new Bad("usual-ahead", () -> with(1000, m60, 4, m30, d3, Duration.ofMinutes(15).plus(s), d90, 1000)),
                new Bad("alias-lookback", () -> with(1000, m60, 4, m30, d3, m2, Duration.ofDays(1).minus(s), 1000)),
                new Bad("alias-lookback", () -> with(1000, m60, 4, m30, d3, m2, Duration.ofDays(365).plus(s), 1000)),
                new Bad("overpay-min-bp", () -> with(1000, m60, 4, m30, d3, m2, d90, 99)),
                new Bad("overpay-min-bp", () -> with(1000, m60, 4, m30, d3, m2, d90, 10_001)))) {
            assertThatThrownBy(bad.make::run).as(bad.key).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("app.suspicion." + bad.key);
        }
    }

    @Test
    void a_finding_is_well_formed_by_construction() {
        UUID p = UUID.randomUUID();
        UUID o = UUID.randomUUID();
        assertThat(Finding.payment(FlagCode.DUPLICATE_TX, p).isPair()).isFalse();
        assertThat(Finding.pair(FlagCode.OVERPAYMENT, p, o).isPair()).isTrue();
        assertThat(Finding.order(FlagCode.SENDER_ALIAS, o).isPair()).isFalse();
        assertThatThrownBy(() -> new Finding(FlagCode.DUPLICATE_TX, SubjectType.PAYMENT, null, o)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Finding(FlagCode.SENDER_ALIAS, SubjectType.ORDER, p, o)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Finding(FlagCode.SENDER_ALIAS, SubjectType.ORDER, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Finding.pair(FlagCode.OVERPAYMENT, p, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_context_is_bounded_and_prints_no_name() {
        Instant now = Instant.now();
        List<SenderRecord> tooMany = java.util.Collections.nCopies(PaymentContext.MAX_SENDER_RECORDS + 1, new SenderRecord("Ana", now));
        assertThatThrownBy(() -> new PaymentContext(UUID.randomUUID(), "RECEIVED", now, now, "h", "Ana", tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PaymentContext(UUID.randomUUID(), "RECEIVED", now, now, "secret-hash", "Secret Name", List.of(new SenderRecord("Other Name", now))).toString())
                .doesNotContain("Secret").doesNotContain("secret-hash").doesNotContain("Other");
        assertThat(new OrderContext(UUID.randomUUID(), "Secret Name", "Other Name").toString()).doesNotContain("Secret").doesNotContain("Other");
        assertThat(new SenderRecord("Secret Name", now).toString()).doesNotContain("Secret");
    }
}
