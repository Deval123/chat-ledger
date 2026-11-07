package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The limiter with a clock the test moves: burst, refill, retry hint, independent keys, reset, and
 * bounded memory.
 *
 * <p>Requirement: 9.1 (rate limiting). Task 3.9.
 */
class InMemoryRateLimiterTest {

    private final MutableClock clock = new MutableClock();

    private InMemoryRateLimiter limiter(String rule, long maxKeys) {
        return new InMemoryRateLimiter(clock, Map.of("r", RateRule.parse("r", rule)), maxKeys, Duration.ofHours(1));
    }

    @Test
    void the_burst_is_allowed_then_the_next_request_is_refused_with_a_retry_hint() {
        InMemoryRateLimiter limiter = limiter("5/15m", 100);
        for (int i = 1; i <= 5; i++) {
            assertThat(limiter.tryAcquire("r", "a").allowed()).as("attempt " + i).isTrue();
        }
        RateDecision refused = limiter.tryAcquire("r", "a");
        assertThat(refused.allowed()).isFalse();
        // One token every 3 minutes.
        assertThat(refused.retryAfterSeconds()).isBetween(170L, 181L);
    }

    @Test
    void tokens_come_back_over_time_but_never_beyond_the_burst() {
        InMemoryRateLimiter limiter = limiter("5/15m", 100);
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("r", "a");
        }
        assertThat(limiter.tryAcquire("r", "a").allowed()).isFalse();
        clock.advance(Duration.ofMinutes(3).plusSeconds(1));
        assertThat(limiter.tryAcquire("r", "a").allowed()).as("one token refilled").isTrue();
        assertThat(limiter.tryAcquire("r", "a").allowed()).isFalse();

        clock.advance(Duration.ofDays(2));
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (limiter.tryAcquire("r", "a").allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).as("a long wait gives the burst back, not more").isEqualTo(5);
    }

    @Test
    void burst_and_rate_are_independent() {
        // 60 a minute, but only 30 at once.
        InMemoryRateLimiter limiter = limiter("60/1m/30", 100);
        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (limiter.tryAcquire("r", "t").allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(30);
        clock.advance(Duration.ofSeconds(10));
        allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (limiter.tryAcquire("r", "t").allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).as("ten seconds at one a second").isEqualTo(10);
    }

    @Test
    void one_key_does_not_affect_another() {
        InMemoryRateLimiter limiter = limiter("2/1m", 100);
        limiter.tryAcquire("r", "tenant-a");
        limiter.tryAcquire("r", "tenant-a");
        assertThat(limiter.tryAcquire("r", "tenant-a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("r", "tenant-b").allowed()).isTrue();
    }

    @Test
    void reset_gives_the_full_allowance_back() {
        InMemoryRateLimiter limiter = limiter("2/1m", 100);
        limiter.tryAcquire("r", "a");
        limiter.tryAcquire("r", "a");
        assertThat(limiter.tryAcquire("r", "a").allowed()).isFalse();
        limiter.reset("r", "a");
        assertThat(limiter.tryAcquire("r", "a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("r", "a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("r", "a").allowed()).isFalse();
    }

    @Test
    void memory_is_bounded_however_many_keys_arrive() {
        InMemoryRateLimiter limiter = limiter("5/1m", 200);
        for (int i = 0; i < 20_000; i++) {
            limiter.tryAcquire("r", UUID.randomUUID().toString());
        }
        assertThat(limiter.size("r")).isLessThanOrEqualTo(200);
    }

    @Test
    void an_unknown_limiter_name_is_a_programming_error() {
        InMemoryRateLimiter limiter = limiter("5/1m", 10);
        assertThatThrownBy(() -> limiter.tryAcquire("nope", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.reset("nope", "a")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rules_are_parsed_and_bad_ones_are_refused() {
        assertThat(RateRule.parse("x", "10/10m")).isEqualTo(new RateRule(10, 10, Duration.ofMinutes(10)));
        assertThat(RateRule.parse("x", "60/1m/30")).isEqualTo(new RateRule(30, 60, Duration.ofMinutes(1)));
        for (String bad : new String[]{"", "10", "x/1m", "10/zz", "0/1m", "10/0s", "-1/1m", "10/1m/0", "1/1m/2/3"}) {
            assertThatThrownBy(() -> RateRule.parse("limit", bad)).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("limit");
        }
    }
}
