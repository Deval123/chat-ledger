package dev.chatledger.flags;

import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.SuspicionConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Set;

/**
 * The suspicion thresholds, all with the approved defaults (task 7, section 4). They are checked by {@link SuspicionConfig} when the
 * application starts; a value out of range stops it.
 *
 * <pre>
 * app.suspicion.near-miss-min=1000              (minor units)
 * app.suspicion.before-order-tolerance=60m
 * app.suspicion.burst-count=4
 * app.suspicion.burst-window=30m
 * app.suspicion.usual-age=3d
 * app.suspicion.usual-ahead=2m
 * app.suspicion.alias-lookback=90d
 * app.suspicion.overpay-min-bp=1000             (basis points: 1000 is 10%)
 * app.suspicion.disabled=                       (codes, comma separated; empty by default)
 * </pre>
 */
@ConfigurationProperties("app.suspicion")
public record SuspicionProperties(
        @DefaultValue("1000") long nearMissMin,
        @DefaultValue("60m") Duration beforeOrderTolerance,
        @DefaultValue("4") int burstCount,
        @DefaultValue("30m") Duration burstWindow,
        @DefaultValue("3d") Duration usualAge,
        @DefaultValue("2m") Duration usualAhead,
        @DefaultValue("90d") Duration aliasLookback,
        @DefaultValue("1000") int overpayMinBp,
        @DefaultValue Set<FlagCode> disabled) {

    public SuspicionConfig toConfig() {
        return new SuspicionConfig(nearMissMin, beforeOrderTolerance, burstCount, burstWindow, usualAge, usualAhead, aliasLookback, overpayMinBp,
                disabled == null ? Set.of() : disabled);
    }
}
