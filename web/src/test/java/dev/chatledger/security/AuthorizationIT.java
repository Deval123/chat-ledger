package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each role may reach, end to end: URL rules and method rules together, with a seller as the
 * positive control for every denial of a helper.
 *
 * <p>Requirement: 9.1 (roles). Task 3.7.
 */
class AuthorizationIT extends AbstractWebIT {

    @Test
    void a_helper_reaches_the_board_and_records_but_nothing_else() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser b = signedIn(w.slug(), helper.email(), helper.password());
        String csrf = Browser.csrf(b.get("/app"));

        assertThat(b.get("/app").status()).isEqualTo(200);
        assertThat(b.get("/app").body()).contains("<span class=\"chip\">Helper</span>");
        assertThat(b.post("/app/probe/record", Map.of("_csrf", csrf)).status()).isEqualTo(200);

        assertThat(b.get("/admin/probe/export").status()).as("export").isEqualTo(403);
        assertThat(b.post("/admin/probe/delete", Map.of("_csrf", csrf)).status()).as("delete tenant data").isEqualTo(403);
        assertThat(b.get("/admin/probe/users").status()).as("manage users").isEqualTo(403);
        assertThat(b.get("/admin/probe/ledger").status()).as("view ledger").isEqualTo(403);
        assertThat(b.get("/admin/anything-at-all").status()).as("any admin path").isEqualTo(403);
        assertThat(b.get("/admin").status()).as("the admin root").isEqualTo(403);
        assertThat(b.get("/admin/").status()).isEqualTo(403);
        assertThat(b.get("/admin/probe/export/").status()).as("trailing slash").isEqualTo(403);
        assertThat(b.get("/admin/probe/export;x=1").status()).as("path parameter").isIn(400, 403);
        assertThat(b.get("/app/../admin/probe/export").status()).as("dot segments").isIn(400, 403, 404);
        assertThat(b.get("/%61dmin/probe/export").status()).as("encoded path").isIn(400, 403);
    }

    @Test
    void a_seller_reaches_every_probe() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        String csrf = Browser.csrf(b.get("/app"));
        assertThat(b.get("/admin/probe/export").status()).isEqualTo(200);
        assertThat(b.post("/admin/probe/delete", Map.of("_csrf", csrf)).status()).isEqualTo(200);
        assertThat(b.get("/admin/probe/users").status()).isEqualTo(200);
        assertThat(b.get("/admin/probe/ledger").status()).isEqualTo(200);
        assertThat(b.post("/app/probe/record", Map.of("_csrf", csrf)).status()).isEqualTo(200);
    }

    @Test
    void the_refusal_does_not_say_why() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser b = signedIn(w.slug(), helper.email(), helper.password());
        Browser.Response denied = b.get("/admin/probe/export");
        assertThat(denied.body()).isEqualTo("Forbidden");
        assertThat(denied.body()).doesNotContainIgnoringCase("EXPORT_DATA").doesNotContainIgnoringCase("role");
    }

    @Test
    void the_session_holds_the_database_role_not_a_claim_from_the_request() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser b = signedIn(w.slug(), helper.email(), helper.password());
        Browser.Response r = b.get("/admin/probe/export", "X-Role", "SELLER", "X-Forwarded-User", w.email());
        assertThat(r.status()).isEqualTo(403);
        assertThat(b.get("/app/probe/whoami").body()).endsWith("HELPER");
    }
}
