package dev.chatledger.security;

import dev.chatledger.demo.ops.DemoWorld;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo's limits as shipped (task 9, D13, D23 at 0 hops): five claims an hour from one address, and a spoofed
 * {@code X-Forwarded-For} does not change the key; the page's own limit (the step limit is in {@code DemoStepLimitIT}). A refused claim answers 429 with
 * {@code Retry-After} and takes no workspace.
 *
 * <p>Requirements 7, 9.2. Task 9.
 */
@TestPropertySource(properties = {"app.demo.page-per-address=12/10m", "app.demo.claim-per-address=5/1h", "app.demo.claim-total=60/1h",
        "app.demo.scenario-per-tenant=1000000/1m", "app.security.trusted-proxy-hops=0"})
class DemoLimitsIT extends AbstractDemoWebIT {

    /** One click from a new browser, with a forwarded header the client chose. */
    private Browser.Response claimAs(String forwarded) {
        Browser b = browser();
        Browser.Response page = b.get("/demo", "X-Forwarded-For", forwarded);
        return b.post("/demo", Map.of("_csrf", Browser.csrf(page)), "X-Forwarded-For", forwarded);
    }

    @Test
    void the_sixth_claim_from_one_address_is_refused_whatever_forwarded_header_it_sends_and_the_page_has_its_own_limit() {
        DemoWorld.retireEverythingWaiting();   // an empty pool: a claim that passes the limit is answered "busy", which spends it all the same
        for (int i = 0; i < 5; i++) {
            Browser.Response r = claimAs("203.0.113." + i);
            assertThat(r.status()).as("claim " + i).isEqualTo(302);
            assertThat(path(r)).isEqualTo("/demo?busy");
        }
        Browser.Response sixth = claimAs("198.51.100.77");
        assertThat(sixth.status()).as("a spoofed header is not a new address at 0 hops").isEqualTo(429);
        assertThat(sixth.header("retry-after")).isNotNull();
        assertThat(loggedText()).contains("limit=demo-claim client=127.0.0.1");
        // Twelve page requests have been made (six pages, six posts): the next one is refused before CSRF is looked at.
        Browser.Response page = browser().get("/demo");
        assertThat(page.status()).isEqualTo(429);
        assertThat(browser().post("/demo", Map.of()).status()).as("before CSRF").isEqualTo(429);
    }

}
