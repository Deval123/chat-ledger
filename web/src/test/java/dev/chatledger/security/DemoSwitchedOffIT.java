package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo is off by default (task 9, D1): on a regular deployment every demo path answers 404 like any unknown page, for an anonymous
 * visitor and for a signed-in seller; the button still needs its CSRF token; the app's address and the sign-in page say nothing about a
 * demo; the board shows no demo chip; and the entry point is the sign-in page.
 *
 * <p>Requirements 7, 9.1. Task 9.
 */
class DemoSwitchedOffIT extends BoardIT {

    @Test
    void every_demo_path_is_unknown_and_nothing_points_at_a_demo() {
        Browser anonymous = browser();
        assertThat(anonymous.get("/demo").status()).isEqualTo(404);
        Browser.Response login = anonymous.get("/login");
        assertThat(login.body()).doesNotContain("/demo").doesNotContain("Try the demo");
        assertThat(anonymous.post("/demo", Map.of("_csrf", Browser.csrf(login))).status()).isEqualTo(404);
        assertThat(anonymous.post("/demo", Map.of()).status()).as("CSRF first").isEqualTo(403);
        Browser.Response root = anonymous.get("/");
        assertThat(path(root)).isEqualTo("/app");
        assertThat(path(anonymous.get("/app"))).isEqualTo("/login");

        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        assertThat(seller.get("/app/demo").status()).isEqualTo(404);
        assertThat(post(seller, "/app/demo/scenarios/happy-path/both", Map.of()).status()).isEqualTo(404);
        assertThat(seller.get("/demo").status()).isEqualTo(404);
        assertThat(seller.get("/app").body()).doesNotContain("Demo, made-up data").doesNotContain("/app/demo");
        assertThat(one("SELECT count(*)::text FROM orders WHERE tenant_id = ?", w.tenantId())).isEqualTo("0");
        assertThat(one("SELECT demo_mode()")).isEqualTo("REGULAR");
    }
}
