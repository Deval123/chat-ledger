package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address limits of the filter use the client address of D23 (task 9): behind one trusted proxy two clients are two keys and one
 * client is one key; with no trusted proxy every request from the socket is one key whatever the header says; the demo page is limited
 * only when the demo is on. No database.
 */
class RateLimitFilterTest {

    private static RateLimitFilter filter(int hops, boolean demo) {
        RateLimits limits = new RateLimits(new InMemoryRateLimiter(new MutableClock(), Map.of(
                RateLimits.LOGIN_IP, RateRule.parse("t", "1/1h"), RateLimits.LOGIN_PAGE_IP, RateRule.parse("t", "1/1h"),
                RateLimits.DEMO_PAGE_IP, RateRule.parse("t", "1/1h")), 100, Duration.ofHours(1)));
        return new RateLimitFilter(limits, new SecurityEvents(), hops, demo);
    }

    private static int status(RateLimitFilter f, String method, String path, String forwarded) throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setServletPath(path);
        r.setRemoteAddr("10.0.0.1");
        if (forwarded != null) {
            r.addHeader("X-Forwarded-For", forwarded);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        f.doFilter(r, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void behind_one_trusted_proxy_each_client_is_its_own_key_for_sign_in() throws Exception {
        RateLimitFilter f = filter(1, false);
        assertThat(status(f, "POST", "/login", "203.0.113.1")).isEqualTo(200);
        assertThat(status(f, "POST", "/login", "203.0.113.2")).as("another client").isEqualTo(200);
        assertThat(status(f, "POST", "/login", "6.6.6.6, 203.0.113.1")).as("the same client, whatever it wrote first").isEqualTo(429);
    }

    @Test
    void with_no_trusted_proxy_the_header_changes_nothing() throws Exception {
        RateLimitFilter f = filter(0, false);
        assertThat(status(f, "GET", "/login", "203.0.113.1")).isEqualTo(200);
        assertThat(status(f, "GET", "/login", "203.0.113.2")).isEqualTo(429);
    }

    @Test
    void the_demo_page_is_limited_only_when_the_demo_is_on() throws Exception {
        RateLimitFilter on = filter(0, true);
        assertThat(status(on, "GET", "/demo", null)).isEqualTo(200);
        assertThat(status(on, "POST", "/demo", null)).isEqualTo(429);
        RateLimitFilter off = filter(0, false);
        assertThat(status(off, "GET", "/demo", null)).isEqualTo(200);
        assertThat(status(off, "GET", "/demo", null)).isEqualTo(200);
    }
}
