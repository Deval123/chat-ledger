package dev.chatledger.suspicion;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The thresholds of the rules, each bounded. A value out of its range is refused here, so a bad setting stops the application at startup
 * instead of switching a rule off or making it fire on everything. Percentages are basis points (integers), never floating point.
 *
 * @param disabled the rules the operator switched off (a noisy rule is turned off here, not by the seller resolving it again and again)
 */
public record SuspicionConfig(long nearMissMinMinor, Duration beforeOrderTolerance, int burstCount, Duration burstWindow, Duration usualAge,
                              Duration usualAhead, Duration aliasLookback, int overpayMinBasisPoints, Set<FlagCode> disabled) {

    public static final long MAX_MINOR = 1_000_000_000_000L;

    public SuspicionConfig {
        range("near-miss-min", nearMissMinMinor, 1, MAX_MINOR);
        range("before-order-tolerance", beforeOrderTolerance, Duration.ofMinutes(10), Duration.ofHours(24));
        range("burst-count", burstCount, 2, 20);
        range("burst-window", burstWindow, Duration.ofMinutes(5), Duration.ofHours(24));
        range("usual-age", usualAge, Duration.ofDays(1), Duration.ofDays(34));
        range("usual-ahead", usualAhead, Duration.ZERO, Duration.ofMinutes(15));
        range("alias-lookback", aliasLookback, Duration.ofDays(1), Duration.ofDays(365));
        range("overpay-min-bp", overpayMinBasisPoints, 100, 10_000);
        Objects.requireNonNull(disabled, "disabled");
        disabled = disabled.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(disabled));
    }

    /** The approved defaults: every rule on. */
    public static SuspicionConfig defaults() {
        return new SuspicionConfig(1000, Duration.ofMinutes(60), 4, Duration.ofMinutes(30), Duration.ofDays(3), Duration.ofMinutes(2),
                Duration.ofDays(90), 1000, Set.of());
    }

    public boolean enabled(FlagCode code) {
        return !disabled.contains(code);
    }

    private static void range(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("app.suspicion." + name + " must be between " + min + " and " + max);
        }
    }

    private static void range(String name, Duration value, Duration min, Duration max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException("app.suspicion." + name + " must be between " + min + " and " + max);
        }
    }
}
