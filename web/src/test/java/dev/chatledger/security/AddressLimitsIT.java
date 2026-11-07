package dev.chatledger.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-address limits: ten sign-in attempts and thirty sign-in page loads per ten minutes. They
 * count before the CSRF check, so leaving out the token does not give free attempts.
 *
 * <p>Requirement: 9.1 (rate limits). Task 3.9.
 */
@TestPropertySource(properties = {
        "app.security.limits.login-per-ip=10/10m",
        "app.security.limits.login-page-per-ip=30/10m"})
class AddressLimitsIT extends AbstractWebIT {

    @Autowired
    RateLimiter limiter;

    @BeforeEach
    void freshAllowance() {
        for (String address : new String[]{"127.0.0.1", "0:0:0:0:0:0:0:1"}) {
            limiter.reset(RateLimits.LOGIN_IP, address);
            limiter.reset(RateLimits.LOGIN_PAGE_IP, address);
        }
    }

    @Test
    void the_eleventh_sign_in_post_from_one_address_is_refused_even_without_a_csrf_token() {
        Browser b = browser();
        for (int i = 1; i <= 10; i++) {
            // No token: the CSRF filter refuses each of these, but each one still counts.
            assertThat(b.post("/login", Map.of("workspace", "x", "email", "x@example.test", "password", "x")).status())
                    .as("request " + i).isEqualTo(403);
        }
        Browser.Response refused = b.post("/login", Map.of("workspace", "x", "email", "x@example.test", "password", "x"));
        assertThat(refused.status()).isEqualTo(429);
        assertThat(Long.parseLong(refused.header("retry-after"))).isBetween(1L, 60L);
        assertThat(refused.body()).isEqualTo("Too many requests. Try again shortly.");
        assertSecurityHeaders(refused, "429 by address");

        // One token every minute.
        clock.advance(Duration.ofSeconds(61));
        assertThat(b.post("/login", Map.of("workspace", "x", "email", "x@example.test", "password", "x")).status()).isEqualTo(403);
    }

    @Test
    void the_thirty_first_load_of_the_sign_in_page_is_refused() {
        Browser b = browser();
        for (int i = 1; i <= 30; i++) {
            assertThat(b.get("/login").status()).as("load " + i).isEqualTo(200);
        }
        Browser.Response refused = b.get("/login");
        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.header("retry-after")).isNotNull();
        assertThat(refused.header("set-cookie")).as("a refused load creates no session").isNull();
    }

    @Test
    void the_two_address_limits_are_separate() {
        Browser b = browser();
        for (int i = 0; i < 30; i++) {
            b.get("/login");
        }
        assertThat(b.get("/login").status()).isEqualTo(429);
        assertThat(b.post("/login", Map.of("workspace", "x")).status()).as("posting is a different limit").isEqualTo(403);
    }

    @Test
    void a_forged_forwarded_header_does_not_choose_the_key() {
        Browser b = browser();
        for (int i = 0; i < 10; i++) {
            b.post("/login", Map.of("workspace", "x"), "X-Forwarded-For", "203.0.113." + i);
        }
        assertThat(b.post("/login", Map.of("workspace", "x"), "X-Forwarded-For", "198.51.100.77").status())
                .as("a new forwarded address does not give a new allowance").isEqualTo(429);
    }
}
