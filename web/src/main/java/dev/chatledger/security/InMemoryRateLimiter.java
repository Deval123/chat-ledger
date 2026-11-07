package dev.chatledger.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Token buckets in memory, one bounded cache per rule. The number of keys is capped and idle keys
 * expire, so a flood of distinct keys cannot grow memory without limit.
 *
 * <p>Limits, named: one node only; a restart clears every counter; with N nodes each limit is
 * multiplied by N; and when the cap is exceeded the least recently used key is dropped, which gives
 * that key its full allowance back (an attacker needs more distinct keys than the cap to use this).
 */
public final class InMemoryRateLimiter implements RateLimiter {

    private final Clock clock;
    private final Map<String, RateRule> rules;
    private final Map<String, Cache<String, Bucket>> caches = new HashMap<>();

    public InMemoryRateLimiter(Clock clock, Map<String, RateRule> rules, long maxKeysPerRule, Duration idleExpiry) {
        this.clock = clock;
        this.rules = Map.copyOf(rules);
        for (String name : rules.keySet()) {
            // Maintenance runs on the calling thread: it is cheap, and it makes the bound deterministic.
            caches.put(name, Caffeine.newBuilder().maximumSize(maxKeysPerRule).expireAfterAccess(idleExpiry)
                    .executor(Runnable::run).build());
        }
    }

    @Override
    public RateDecision tryAcquire(String limiter, String key) {
        RateRule rule = rule(limiter);
        Bucket bucket = caches.get(limiter).get(key, k -> new Bucket(rule.capacity(), clock.millis()));
        return bucket.tryAcquire(rule, clock.millis());
    }

    @Override
    public void reset(String limiter, String key) {
        rule(limiter);
        caches.get(limiter).invalidate(key);
    }

    /** Number of keys held for a rule (for tests and monitoring). */
    public long size(String limiter) {
        Cache<String, Bucket> cache = caches.get(limiter);
        cache.cleanUp();
        return cache.estimatedSize();
    }

    private RateRule rule(String limiter) {
        RateRule rule = rules.get(limiter);
        if (rule == null) {
            throw new IllegalArgumentException("no rate limit called " + limiter);
        }
        return rule;
    }

    private static final class Bucket {
        private double available;
        private long lastMillis;

        Bucket(int capacity, long nowMillis) {
            this.available = capacity;
            this.lastMillis = nowMillis;
        }

        synchronized RateDecision tryAcquire(RateRule rule, long nowMillis) {
            double perMillis = (double) rule.tokens() / rule.period().toMillis();
            long elapsed = Math.max(0, nowMillis - lastMillis);
            available = Math.min(rule.capacity(), available + elapsed * perMillis);
            lastMillis = nowMillis;
            if (available >= 1) {
                available -= 1;
                return RateDecision.permit();
            }
            double millisUntilOne = (1 - available) / perMillis;
            return RateDecision.deny((long) Math.ceil(millisUntilOne / 1000.0));
        }
    }
}
