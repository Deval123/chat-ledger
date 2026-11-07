package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-account sign-in limit (5 per 15 minutes) and the per-tenant ingestion limit (60 per minute,
 * burst 30), on the real server with the real defaults and a clock the test moves.
 *
 * <p>Requirement: 9.1 (rate limits). Task 3.9.
 */
@TestPropertySource(properties = "app.security.limits.login-per-account=5/15m")
class RateLimitsIT extends AbstractWebIT {

    private Browser.Response attempt(String workspace, String email, String password) {
        return signIn(browser(), workspace, email, password);
    }

    private static Map<String, List<String>> comparable(Browser.Response r) {
        Map<String, List<String>> out = new TreeMap<>();
        r.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase();
            if (!lower.equals("date") && !lower.equals("retry-after") && !lower.equals("set-cookie")) {
                out.put(lower, new ArrayList<>(values));
            }
        });
        return out;
    }

    @Test
    void the_sixth_attempt_for_one_account_is_refused_and_even_the_right_password_does_not_get_in() {
        Workspace w = newWorkspace();
        for (int i = 1; i <= 5; i++) {
            Browser.Response r = attempt(w.slug(), w.email(), newPassword());
            assertThat(r.status()).as("attempt " + i).isEqualTo(302);
            assertThat(r.header("location")).endsWith("/login?error");
        }
        Browser b = browser();
        Browser.Response refused = signIn(b, w.slug(), w.email(), w.password());   // the right password
        assertThat(refused.status()).isEqualTo(429);
        assertThat(Long.parseLong(refused.header("retry-after"))).isBetween(1L, 900L);
        assertThat(refused.body()).isEqualTo("Too many requests. Try again shortly.");
        assertThat(b.get("/app").status()).as("and no session was created").isEqualTo(302);
        assertSecurityHeaders(refused, "429");
    }

    @Test
    void the_refusal_is_identical_for_an_account_that_does_not_exist() {
        Workspace w = newWorkspace();
        String ghostWorkspace = "ghost-" + unique();
        String ghostEmail = "ghost-" + unique() + "@example.test";
        Browser.Response real = null;
        Browser.Response ghost = null;
        for (int i = 1; i <= 6; i++) {
            real = attempt(w.slug(), w.email(), newPassword());
            ghost = attempt(ghostWorkspace, ghostEmail, newPassword());
        }
        assertThat(real.status()).isEqualTo(429);
        assertThat(ghost.status()).isEqualTo(429);
        assertThat(ghost.body()).isEqualTo(real.body());
        assertThat(comparable(ghost)).isEqualTo(comparable(real));
        assertThat(ghost.header("retry-after")).isNotNull();
    }

    @Test
    void the_limit_belongs_to_the_account_not_to_everyone() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Workspace other = newWorkspace();
        for (int i = 0; i < 6; i++) {
            attempt(w.slug(), w.email(), newPassword());
        }
        assertThat(attempt(w.slug(), w.email(), newPassword()).status()).isEqualTo(429);
        // Another user of the same workspace, and the same email in another workspace, are untouched.
        assertThat(attempt(w.slug(), helper.email(), helper.password()).header("location")).endsWith("/app");
        assertThat(attempt(other.slug(), w.email(), newPassword()).status()).isEqualTo(302);
    }

    @Test
    void tokens_return_over_time() {
        Workspace w = newWorkspace();
        for (int i = 0; i < 6; i++) {
            attempt(w.slug(), w.email(), newPassword());
        }
        assertThat(attempt(w.slug(), w.email(), w.password()).status()).isEqualTo(429);
        clock.advance(Duration.ofMinutes(3).plusSeconds(5));   // one token every 3 minutes
        assertThat(attempt(w.slug(), w.email(), w.password()).header("location")).endsWith("/app");
    }

    @Test
    void a_successful_sign_in_gives_the_allowance_back() {
        Workspace w = newWorkspace();
        for (int i = 0; i < 4; i++) {
            assertThat(attempt(w.slug(), w.email(), newPassword()).status()).isEqualTo(302);
        }
        assertThat(attempt(w.slug(), w.email(), w.password()).header("location")).endsWith("/app");
        for (int i = 0; i < 4; i++) {
            assertThat(attempt(w.slug(), w.email(), newPassword()).status()).as("after the reset, failure " + (i + 1)).isEqualTo(302);
        }
    }

    // ── Ingestion, per tenant ────────────────────────────────────────────────

    @Test
    void ingestion_is_limited_per_tenant_and_one_tenants_limit_does_not_touch_another() {
        Workspace a = newWorkspace();
        Member helperOfA = addUser(a, Role.HELPER);
        Workspace b = newWorkspace();
        Browser sellerA = signedIn(a.slug(), a.email(), a.password());
        Browser sellerB = signedIn(b.slug(), b.email(), b.password());
        String tokenA = Browser.csrf(sellerA.get("/app"));
        String tokenB = Browser.csrf(sellerB.get("/app"));

        for (int i = 1; i <= 30; i++) {
            assertThat(sellerA.post("/app/probe/ingest", Map.of(), "X-CSRF-TOKEN", tokenA).status()).as("burst " + i).isEqualTo(200);
        }
        Browser.Response refused = sellerA.post("/app/probe/ingest", Map.of(), "X-CSRF-TOKEN", tokenA);
        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.header("retry-after")).isNotNull();

        assertThat(sellerB.post("/app/probe/ingest", Map.of(), "X-CSRF-TOKEN", tokenB).status()).as("tenant B").isEqualTo(200);

        // The limit is the tenant's, so a second user of tenant A shares it.
        Browser helper = signedIn(a.slug(), helperOfA.email(), helperOfA.password());
        String tokenH = Browser.csrf(helper.get("/app"));
        assertThat(helper.post("/app/probe/ingest", Map.of(), "X-CSRF-TOKEN", tokenH).status()).isEqualTo(429);

        clock.advance(Duration.ofSeconds(3));   // sixty a minute is one a second
        assertThat(sellerA.post("/app/probe/ingest", Map.of(), "X-CSRF-TOKEN", tokenA).status()).isEqualTo(200);
    }
}
