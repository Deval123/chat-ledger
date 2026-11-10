package dev.chatledger.security;

import dev.chatledger.demo.ops.DemoWorld;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behind one trusted proxy (task 9, D23): each judge is a key of its own, so the sixth judge is not refused; one judge is still
 * limited however it dresses its own part of the header; and the global limit holds for everyone together. Here the test plays the
 * proxy: it appends the judge's address as the last entry, after whatever the "client" wrote.
 *
 * <p>Requirements 7, 9.2. Task 9.
 */
@TestPropertySource(properties = {"app.demo.page-per-address=1000000/1m", "app.demo.claim-per-address=5/1h", "app.demo.claim-total=12/1h",
        "app.demo.scenario-per-tenant=1000000/1m", "app.security.trusted-proxy-hops=1"})
class DemoProxyIT extends AbstractDemoWebIT {

    private Browser.Response claimAs(String forwarded) {
        Browser b = browser();
        Browser.Response page = b.get("/demo", "X-Forwarded-For", forwarded);
        return b.post("/demo", Map.of("_csrf", Browser.csrf(page)), "X-Forwarded-For", forwarded);
    }

    @Test
    void six_judges_behind_the_proxy_are_six_keys_one_judge_is_still_limited_and_the_total_holds() {
        DemoWorld.retireEverythingWaiting();   // an empty pool: every claim that passes the limits is answered "busy"
        for (int judge = 1; judge <= 6; judge++) {
            assertThat(claimAs("203.0.113." + judge).status()).as("judge " + judge).isEqualTo(302);
        }
        // One judge, whatever it puts before the proxy's entry: five claims, then refused (and that refusal spends no global token).
        for (int i = 0; i < 5; i++) {
            assertThat(claimAs("10.9.9." + i + ", 198.51.100.7").status()).as("claim " + i).isEqualTo(302);
        }
        assertThat(claimAs("10.9.9.200, 198.51.100.7").status()).isEqualTo(429);
        assertThat(loggedText()).contains("limit=demo-claim client=198.51.100.7");
        // Eleven claims spent the global limit of twelve: one more judge passes, the next does not.
        assertThat(claimAs("192.0.2.1").status()).isEqualTo(302);
        assertThat(claimAs("192.0.2.2").status()).as("the total for everyone").isEqualTo(429);
    }
}
