package dev.chatledger.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application as it runs on the VPS (task 13), the {@code prod} profile over plain HTTP, as it sees requests behind the tunnel:
 * HSTS on every response (D10), relative redirects (so no {@code http://} link leaks behind the tunnel), the CSP unchanged, the actuator not on the public port (D18), {@code /health} minimal, Flyway off
 * (D4), the cookie still {@code __Host-} and Secure.
 *
 * <p>Task 13.
 */
@ActiveProfiles("prod")
// A random management port, so parallel runs do not collide; the address is the prod profile's own.
@TestPropertySource(properties = "management.server.port=0")
class ProdProfileIT extends AbstractWebIT {

    private static final String HSTS = "max-age=31536000 ; includeSubDomains";

    @Autowired
    ApplicationContext context;

    private static String hsts(Browser.Response r) {
        String value = r.header("strict-transport-security");
        return value == null ? null : value.replace(" ;", ";").replace(";", " ;");
    }

    @Test
    void every_response_carries_hsts_over_plain_http_and_the_csp_is_unchanged() {
        Browser b = browser();
        for (String path : new String[]{"/login", "/health", "/assets/app.css", "/no-such-page", "/app"}) {
            Browser.Response r = b.get(path);
            assertThat(hsts(r)).as(path).isEqualTo(HSTS);
            assertThat(r.header("content-security-policy")).as(path).isEqualTo(EXPECTED_CSP);
        }
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        Browser.Response board = seller.get("/app");
        assertThat(board.status()).isEqualTo(200);
        assertThat(hsts(board)).isEqualTo(HSTS);
        assertThat(seller.cookie(COOKIE)).as("the __Host- cookie is still set and sent").isNotNull();
        Browser.Response login = browser().get("/login");
        assertThat(String.join(" ", login.setCookies())).contains(COOKIE).contains("Secure").contains("HttpOnly").contains("SameSite=Strict")
                .doesNotContain("Domain=");
        assertThat(browser().get("/app").header("location")).as("redirects stay relative").isEqualTo("/login");
        assertThat(browser().get("/").header("location")).isEqualTo("/app");
        Browser.Response out = seller.post("/logout", Map.of("_csrf", Browser.csrf(seller.get("/app"))));
        assertThat(out.header("location")).isEqualTo("/login?logout");
    }

    @Test
    void the_public_port_has_no_actuator_and_health_says_one_word() {
        Browser b = browser();
        for (String path : new String[]{"/actuator/health", "/actuator", "/actuator/env"}) {
            Browser.Response r = b.get(path);
            assertThat(r.status()).as(path).isIn(302, 403, 404);
            assertThat(r.body()).as(path).doesNotContain("UP").doesNotContain("components");
            if (r.status() == 302) {
                assertThat(r.header("location")).as(path + ": only the sign-in page").isEqualTo("/login");
            }
        }
        Browser.Response health = b.get("/health");
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void the_management_server_listens_on_the_loopback_address_only() {
        org.springframework.core.env.Environment env = context.getEnvironment();
        assertThat(env.getProperty("management.server.address")).isEqualTo("127.0.0.1");
        assertThat(env.getProperty("local.management.port", Integer.class)).as("a port of its own")
                .isNotNull().isNotEqualTo(env.getProperty("local.server.port", Integer.class));
    }

    @Test
    void flyway_does_not_run_in_the_web_process() {
        assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
    }
}
