package dev.chatledger.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Security settings, all with safe defaults and all validated at startup.
 *
 * <pre>
 * app.security.session.idle-timeout=30m
 * app.security.session.absolute-timeout=12h
 * app.security.session.anonymous-timeout=10m
 * app.security.user-recheck-interval=60s
 * app.security.hashing.max-concurrent=4
 * app.security.hashing.wait-time=2s
 * app.security.limits.login-per-ip=10/10m          (limit/period[/burst])
 * app.security.limits.login-per-account=5/15m
 * app.security.limits.login-page-per-ip=30/10m
 * app.security.limits.ingestion-per-tenant=60/1m/30
 * app.security.limits.rematch-per-minute=3/1m
 * app.security.limits.rematch-per-hour=20/1h
 * app.security.limits.export-per-tenant=6/10m
 * app.security.limits.actions-per-user=120/1m
 * app.security.size.login=4KB
 * app.security.size.json=16KB
 * app.security.size.other=64KB
 * </pre>
 *
 * <p>A bad value (zero, negative, an absolute timeout shorter than the idle one) stops the
 * application from starting rather than silently weakening a defense.
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(
        @DefaultValue Session session,
        @DefaultValue Hashing hashing,
        @DefaultValue Limits limits,
        @DefaultValue Size size,
        @DefaultValue("60s") Duration userRecheckInterval) {

    public SecurityProperties {
        requirePositive("app.security.user-recheck-interval", userRecheckInterval);
    }

    /** Rate limits as text, parsed (and so validated) when the application starts. */
    public record Limits(
            @DefaultValue("10/10m") String loginPerIp,
            @DefaultValue("5/15m") String loginPerAccount,
            @DefaultValue("30/10m") String loginPagePerIp,
            @DefaultValue("60/1m/30") String ingestionPerTenant,
            @DefaultValue("3/1m") String rematchPerMinute,
            @DefaultValue("20/1h") String rematchPerHour,
            @DefaultValue("6/10m") String exportPerTenant,
            @DefaultValue("120/1m") String actionsPerUser) {
        public Limits {
            RateRule.parse("app.security.limits.login-per-ip", loginPerIp);
            RateRule.parse("app.security.limits.login-per-account", loginPerAccount);
            RateRule.parse("app.security.limits.login-page-per-ip", loginPagePerIp);
            RateRule.parse("app.security.limits.ingestion-per-tenant", ingestionPerTenant);
            RateRule.parse("app.security.limits.rematch-per-minute", rematchPerMinute);
            RateRule.parse("app.security.limits.rematch-per-hour", rematchPerHour);
            RateRule.parse("app.security.limits.export-per-tenant", exportPerTenant);
            RateRule.parse("app.security.limits.actions-per-user", actionsPerUser);
        }
    }

    /** Largest accepted request body. Everything above is refused with 413 before it is read. */
    public record Size(
            @DefaultValue("4KB") DataSize login,
            @DefaultValue("16KB") DataSize json,
            @DefaultValue("64KB") DataSize other) {
        public Size {
            for (DataSize size : new DataSize[]{login, json, other}) {
                if (size == null || size.toBytes() < 1 || size.toBytes() > 1024 * 1024) {
                    throw new IllegalArgumentException("app.security.size values must be between 1 byte and 1MB");
                }
            }
        }
    }

    public record Session(
            @DefaultValue("30m") Duration idleTimeout,
            @DefaultValue("12h") Duration absoluteTimeout,
            @DefaultValue("10m") Duration anonymousTimeout) {

        public Session {
            requirePositive("app.security.session.idle-timeout", idleTimeout);
            requirePositive("app.security.session.absolute-timeout", absoluteTimeout);
            requirePositive("app.security.session.anonymous-timeout", anonymousTimeout);
            if (absoluteTimeout.compareTo(idleTimeout) < 0) {
                throw new IllegalArgumentException(
                        "app.security.session.absolute-timeout must not be shorter than the idle timeout");
            }
        }
    }

    public record Hashing(
            @DefaultValue("4") int maxConcurrent,
            @DefaultValue("2s") Duration waitTime) {

        public Hashing {
            if (maxConcurrent < 1) {
                throw new IllegalArgumentException("app.security.hashing.max-concurrent must be at least 1");
            }
            requirePositive("app.security.hashing.wait-time", waitTime);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
    }
}
