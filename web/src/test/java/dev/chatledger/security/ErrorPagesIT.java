package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Error pages say that something went wrong and give the status, and nothing else: no message, path,
 * exception name, stack trace, class name or framework or server version.
 *
 * <p>Requirement: 9.1. Task 3.8.
 */
class ErrorPagesIT extends AbstractWebIT {

    private static final String[] NEVER_IN_AN_ERROR_PAGE = {
            "Whitelabel", "Exception", "Error:", "stack", "trace", "org.springframework", "java.", "jakarta.", "dev.chatledger",
            "Tomcat", "Catalina", "Apache", "Spring", "Thymeleaf", "Hibernate", "PostgreSQL", "postgres", "Flyway",
            "audit_log", "SQL", "timestamp", "\"path\"", "message"};

    private static final String[] HTML = {"Accept", "text/html,application/xhtml+xml"};

    /** Sign in the way a browser does, asking for HTML. */
    private Browser.Response postAsBrowser(Workspace w) {
        Browser b = browser();
        String token = Browser.csrf(b.get("/login"));
        return b.post("/login", Map.of("_csrf", token, "workspace", w.slug(), "email", w.email(), "password", w.password()), HTML);
    }

    private static void assertClean(Browser.Response r, int status, String what) {
        assertThat(r.status()).as(what).isEqualTo(status);
        assertThat(r.body()).as(what).contains("Something went wrong").contains(">" + status + "<");
        for (String forbidden : NEVER_IN_AN_ERROR_PAGE) {
            assertThat(r.body()).as(what + " must not contain " + forbidden).doesNotContain(forbidden);
        }
        assertThat(r.body()).as(what + " has no HTML comments").doesNotContain("<!--");
        assertThat(r.header("content-type")).as(what).startsWith("text/html");
        assertSecurityHeaders(r, what);
    }

    @Test
    void a_missing_page_for_a_signed_in_user_is_a_plain_404_that_does_not_echo_the_path() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        Browser.Response r = b.get("/app/no-such-page-" + unique(), HTML);
        assertClean(r, 404, "404");
        assertThat(r.body()).doesNotContain("no-such-page");
    }

    @Test
    void a_method_that_is_not_allowed_is_a_plain_405() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        String token = Browser.csrf(b.get("/app"));
        assertClean(b.post("/app", Map.of("_csrf", token), HTML), 405, "405");
    }

    @Test
    void a_server_failure_is_a_plain_500_even_when_the_database_error_names_things() {
        Workspace w = newWorkspace();
        String constraint = "block_" + unique();
        asOwner("ALTER TABLE audit_log ADD CONSTRAINT " + constraint
                + " CHECK (tenant_id <> '" + w.tenantId() + "'::uuid) NOT VALID");
        Browser.Response r = postAsBrowser(w);
        assertClean(r, 500, "500");
        assertThat(r.body()).doesNotContain(constraint).doesNotContain(w.email()).doesNotContain(w.slug());
    }

    @Test
    void a_client_that_asks_for_json_gets_only_the_status_and_reason() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        Browser.Response r = b.get("/app/no-such-page", "Accept", "application/json");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body()).isEqualTo("{\"status\":404,\"error\":\"Not Found\"}");
        assertSecurityHeaders(r, "json error");
    }

    @Test
    void the_login_page_after_a_failure_does_not_echo_what_was_typed() {
        Workspace w = newWorkspace();
        Browser b = browser();
        Browser.Response failed = signIn(b, w.slug(), w.email(), newPassword());
        assertThat(failed.header("location")).endsWith("/login?error");
        Browser.Response page = b.get("/login?error");
        assertThat(page.body()).doesNotContain(w.slug()).doesNotContain(w.email());
    }
}
