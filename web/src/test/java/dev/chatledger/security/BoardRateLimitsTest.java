package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The board's limits as the application wires them (the real {@code SecurityConfig.rateLimiter} over the default settings), on a clock the
 * test moves: rematch 3 a minute and 20 an hour per tenant, export 6 per 10 minutes per tenant, 120 actions a minute per user.
 *
 * <p>Requirement: 9.1 (rate limits). Task 8.
 */
class BoardRateLimitsTest {

    private final MutableClock clock = new MutableClock();
    private final RateLimits limits = new RateLimits(new SecurityConfig().rateLimiter(clock,
            new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("app.security", SecurityProperties.class)));

    @Test
    void rematch_is_three_a_minute_per_tenant() {
        UUID tenant = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            assertThat(limits.rematch(tenant).allowed()).as("call " + i).isTrue();
        }
        assertThat(limits.rematch(tenant).allowed()).isFalse();
        assertThat(limits.rematch(UUID.randomUUID()).allowed()).as("another tenant").isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(limits.rematch(tenant).allowed()).isTrue();
    }

    /**
     * The hour limit is a bucket of 20 that refills one call every three minutes. At one call every 21 seconds (a pace the minute limit allows)
     * it runs dry after about 22 calls: never before the 21st, and well before the 30th.
     */
    @Test
    void rematch_is_held_to_twenty_an_hour_at_a_pace_the_minute_limit_allows() {
        UUID tenant = UUID.randomUUID();
        int firstRefusal = -1;
        for (int i = 1; i <= 40 && firstRefusal < 0; i++) {
            if (!limits.rematch(tenant).allowed()) {
                firstRefusal = i;
            }
            clock.advance(Duration.ofSeconds(21));
        }
        assertThat(firstRefusal).isBetween(21, 29);
    }

    @Test
    void export_is_six_per_ten_minutes_per_tenant() {
        UUID tenant = UUID.randomUUID();
        for (int i = 0; i < 6; i++) {
            assertThat(limits.export(tenant).allowed()).isTrue();
        }
        assertThat(limits.export(tenant).allowed()).isFalse();
        assertThat(limits.export(UUID.randomUUID()).allowed()).isTrue();
        clock.advance(Duration.ofMinutes(10));
        assertThat(limits.export(tenant).allowed()).isTrue();
    }

    @Test
    void actions_are_a_hundred_and_twenty_a_minute_per_user() {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 120; i++) {
            assertThat(limits.action(user).allowed()).as("action " + i).isTrue();
        }
        RateDecision refused = limits.action(user);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(limits.action(UUID.randomUUID()).allowed()).isTrue();
    }
}
