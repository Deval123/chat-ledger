package dev.chatledger.security;

import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;

/**
 * One rate limit as a token bucket: it holds up to {@code capacity} tokens (the burst), gets
 * {@code tokens} new ones every {@code period}, and each request costs one.
 *
 * <p>Written in configuration as {@code limit/period} or {@code limit/period/burst}, for example
 * {@code 10/10m} (ten per ten minutes, burst ten) or {@code 60/1m/30} (sixty per minute, burst thirty).
 */
public record RateRule(int capacity, int tokens, Duration period) {

    public RateRule {
        if (capacity < 1 || tokens < 1) {
            throw new IllegalArgumentException("a rate limit needs a limit and a burst of at least 1");
        }
        if (period == null || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("a rate limit needs a positive period");
        }
    }

    public static RateRule parse(String name, String text) {
        try {
            String[] parts = text.strip().split("/");
            if (parts.length < 2 || parts.length > 3) {
                throw new IllegalArgumentException("expected limit/period or limit/period/burst");
            }
            int limit = Integer.parseInt(parts[0].strip());
            Duration period = DurationStyle.SIMPLE.parse(parts[1].strip());
            int burst = parts.length == 3 ? Integer.parseInt(parts[2].strip()) : limit;
            return new RateRule(burst, limit, period);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(name + " is not a valid rate limit (" + e.getMessage() + "), e.g. 10/10m", e);
        }
    }
}
