package dev.chatledger.security;

import dev.chatledger.demo.ops.DemoWorld;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public door of the demo, through the real application on the demo deployment (task 9, D1, D2, D4, D11, D21, D22): the app's
 * address leads to one button; one click hands out one ready workspace, signs its seller in with a renewed session and an audit row; a
 * visitor already in a workspace goes back to it and takes no new one; CSRF is required; an empty pool is a sentence; a retired workspace
 * or an ended lease ends the session; password sign-in cannot reach a demo workspace; a workspace that is not a demo has no scenarios.
 *
 * <p>Requirements 7, 9.1. Task 9.
 */
class DemoEntryIT extends AbstractDemoWebIT {

    private DemoWorld.Workspace ready() {
        DemoWorld.retireEverythingWaiting();
        return DemoWorld.seeded();
    }

    @Test
    void the_apps_address_leads_to_one_button_and_one_click_enters_a_fresh_workspace() {
        DemoWorld.Workspace w = ready();
        Browser b = browser();
        Browser.Response root = b.get("/");
        assertThat(root.status()).isEqualTo(302);
        assertThat(path(root)).isEqualTo("/demo");
        assertThat(path(b.get("/app"))).as("the entry point is the demo page here").isEqualTo("/demo");
        Browser.Response page = b.get("/demo");
        assertThat(page.status()).isEqualTo(200);
        assertSecurityHeaders(page, "/demo");
        assertThat(page.body()).contains("This is a demo with made-up shops, customers and payments. Nothing here is real.")
                .contains(">Try the demo<").doesNotContain("<textarea").doesNotContain("name=\"workspace\"").doesNotContain("type=\"text\"");
        String anonymousSession = b.cookie(COOKIE);
        assertThat(anonymousSession).isNotNull();

        Browser.Response in = b.post("/demo", Map.of("_csrf", Browser.csrf(page)));
        assertThat(in.status()).isEqualTo(302);
        assertThat(path(in)).isEqualTo("/app");
        assertThat(b.cookie(COOKIE)).as("a new session id at sign-in").isNotEqualTo(anonymousSession);
        Browser.Response board = b.get("/app");
        assertThat(board.status()).isEqualTo(200);
        assertThat(board.body()).contains("Demo, made-up data").contains("href=\"/app/demo\"").contains("Matched 1").contains("Flagged");
        assertThat(demoOne("SELECT state || ' ' || (lease_ends_at - claimed_at)::text FROM demo_tenant WHERE tenant_id = ?", w.tenant()))
                .as("the configured lease").isEqualTo("CLAIMED 00:30:00");
        assertThat(demoRows("SELECT actor::text FROM audit_log WHERE tenant_id = ? AND action = 'DEMO_ENTERED'", w.tenant()))
                .containsExactly(w.seller().toString());
        // As in AuthenticationIT: the live session id and the CSRF token never reach the log (the dead pre-sign-in id appears only in
        // Spring Security's DEBUG line about the change, which production does not log).
        String token = Browser.csrf(board);
        assertThat(loggedText()).contains("demo entered").doesNotContain(b.cookie(COOKIE)).doesNotContain(token);
        DemoWorld.retire(w);
    }

    @Test
    void a_visitor_already_in_a_workspace_is_sent_back_to_it_and_takes_no_new_one() {
        DemoWorld.Workspace w = ready();
        Browser b = entered();
        DemoWorld.Workspace spare = DemoWorld.seeded();
        long before = claimed();
        Browser.Response again = b.get("/demo");
        assertThat(again.status()).isEqualTo(302);
        assertThat(path(again)).isEqualTo("/app");
        Browser.Response posted = demoPost(b, "/demo");
        assertThat(posted.status()).isEqualTo(302);
        assertThat(path(posted)).isEqualTo("/app");
        assertThat(claimed()).as("no new lease").isEqualTo(before);
        assertThat(demoOne("SELECT state FROM demo_tenant WHERE tenant_id = ?", spare.tenant())).isEqualTo("READY");
        assertThat(b.get("/app").body()).contains("Demo, made-up data");
        DemoWorld.retire(w);
        DemoWorld.retire(spare);
    }

    @Test
    void the_button_needs_its_csrf_token_and_an_empty_pool_is_a_sentence() {
        DemoWorld.retireEverythingWaiting();
        Browser b = browser();
        Browser.Response page = b.get("/demo");
        long before = claimed();
        assertThat(b.post("/demo", Map.of()).status()).isEqualTo(403);
        assertThat(b.post("/demo", Map.of("_csrf", Browser.csrf(page) + "x")).status()).isEqualTo(403);
        Browser.Response none = b.post("/demo", Map.of("_csrf", Browser.csrf(page)));
        assertThat(none.status()).isEqualTo(302);
        assertThat(path(none)).isEqualTo("/demo?busy");
        assertThat(b.get("/demo?busy").body()).contains("All demo workspaces are in use. Try again in a few minutes.");
        assertThat(path(b.get("/app"))).as("still nobody").isEqualTo("/demo");
        assertThat(claimed()).isEqualTo(before);
    }

    @Test
    void a_retired_workspace_ends_its_session_within_the_recheck_and_the_visitor_can_start_again() {
        DemoWorld.Workspace w = ready();
        Browser b = entered();
        DemoWorld.retire(w);
        clock.advance(Duration.ofSeconds(61));
        Browser.Response after = b.get("/app");
        assertThat(after.status()).isEqualTo(302);
        assertThat(path(after)).isEqualTo("/demo");
        DemoWorld.Workspace next = DemoWorld.seeded();
        Browser.Response page = b.get("/demo");
        assertThat(page.status()).isEqualTo(200);
        assertThat(path(b.post("/demo", Map.of("_csrf", Browser.csrf(page))))).isEqualTo("/app");
        assertThat(demoOne("SELECT state FROM demo_tenant WHERE tenant_id = ?", next.tenant())).isEqualTo("CLAIMED");
        DemoWorld.retire(next);
    }

    @Test
    void right_after_its_workspace_is_retired_a_visitor_may_start_a_new_demo_without_waiting_for_the_recheck() {
        DemoWorld.Workspace w = ready();
        Browser b = entered();
        DemoWorld.retire(w);
        Browser.Response page = b.get("/demo");
        assertThat(page.status()).as("not sent back to a retired workspace").isEqualTo(200);
        assertThat(page.body()).contains(">Try the demo<");
    }

    @Test
    void a_leased_seller_that_is_disabled_or_not_a_seller_is_never_signed_in() {
        for (String change : new String[]{"UPDATE app_user SET disabled_at = now() WHERE id = ?", "UPDATE app_user SET role = 'HELPER' WHERE id = ?"}) {
            DemoWorld.retireEverythingWaiting();
            DemoWorld.Workspace w = DemoWorld.seeded();
            DemoWorld.column(DemoWorld.DB.migrateDataSource(), change, w.seller());
            Browser b = browser();
            Browser.Response page = b.get("/demo");
            Browser.Response in = b.post("/demo", java.util.Map.of("_csrf", Browser.csrf(page)));
            assertThat(path(in)).as(change).isEqualTo("/demo?busy");
            assertThat(path(b.get("/app"))).isEqualTo("/demo");
            assertThat(loggedText()).contains("demo refused reason=not-a-demo-seller");
            DemoWorld.retire(w);
        }
    }

    @Test
    void the_session_ends_with_the_lease_even_if_the_workspace_was_not_retired_yet() {
        DemoWorld.Workspace w = ready();
        Browser b = entered();
        clock.advance(Duration.ofMinutes(29));
        assertThat(b.get("/app").status()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(1));
        assertThat(path(b.get("/app"))).isEqualTo("/demo");
        assertThat(loggedText()).contains("reason=demo-lease-ended");
        DemoWorld.retire(w);
    }

    @Test
    void password_sign_in_cannot_reach_a_demo_workspace() {
        DemoWorld.Workspace w = ready();
        String slug = demoOne("SELECT slug FROM tenant WHERE id = ?", w.tenant());
        String email = demoOne("SELECT email FROM app_user WHERE id = ?", w.seller());
        Browser b = browser();
        Browser.Response login = b.get("/login");
        assertThat(login.body()).contains("href=\"/demo\"").contains("Try the demo");
        Browser.Response tried = b.post("/login", Map.of("_csrf", Browser.csrf(login), "workspace", slug, "email", email, "password", "any-password-at-all"));
        assertThat(path(tried)).isEqualTo("/login?error");
        assertThat(demoOne("SELECT state FROM demo_tenant WHERE tenant_id = ?", w.tenant())).isEqualTo("READY");
        DemoWorld.retire(w);
    }

    @Test
    void a_demo_workspace_takes_made_up_phone_numbers_only_from_the_paste_box_too() {
        DemoWorld.Workspace w = ready();
        Browser b = entered();
        Browser.Response pasted = pasteOrder(b, "Order: R-1\nName: Ana Testa\nPhone: +237 600 000 001\n2 x rice 5kg");
        assertThat(path(pasted)).isEqualTo("/app?n=DEMO_REAL_PHONE");
        assertThat(b.get(path(pasted)).body()).contains("This demo takes made-up phone numbers only: they start with +999. Nothing was recorded.");
        assertThat(demoOne("SELECT count(*)::text FROM orders WHERE tenant_id = ? AND order_ref = 'R-1'", w.tenant())).isEqualTo("0");
        assertThat(path(pasteOrder(b, "Order: R-2\nName: Ana Testa\nPhone: +999 000 000 1777\n2 x rice 5kg"))).startsWith("/app?n=ORDER_RECORDED");
        DemoWorld.retire(w);
    }

    @Test
    void the_scenarios_page_has_seven_scenarios_and_no_text_field_and_a_step_goes_through_the_services() {
        DemoWorld.Workspace w = ready();
        Browser b = entered();
        Browser.Response page = b.get("/app/demo");
        assertThat(page.status()).isEqualTo(200);
        assertSecurityHeaders(page, "/app/demo");
        assertThat(page.body()).contains("1. Happy path").contains("7. Late payment").contains("Simulated chat")
                .doesNotContain("<textarea").doesNotContain("type=\"text\"").doesNotContain("WhatsApp");
        assertThat(page.body().split("/app/demo/scenarios/", -1)).as("three buttons for each of seven").hasSize(22);
        Browser.Response order = demoPost(b, "/app/demo/scenarios/happy-path/order");
        assertThat(path(order)).isEqualTo("/app/demo?n=DEMO_ORDER_SENT#s-happy-path");
        assertThat(b.get("/app/demo?n=DEMO_ORDER_SENT").body()).contains("The order was sent to chat-ledger.").contains("The order was sent. Now send the payment.");
        Browser.Response payment = demoPost(b, "/app/demo/scenarios/happy-path/payment");
        assertThat(path(payment)).isEqualTo("/app?n=DEMO_PAYMENT_SENT");
        assertThat(b.get("/app").body()).contains("Matched 2");
        assertThat(demoRows("SELECT o.status FROM orders o WHERE o.tenant_id = ? AND o.external_id LIKE 'sim:%'", w.tenant())).containsExactly("PAID");
        assertThat(demoRows("SELECT DISTINCT a.actor::text FROM audit_log a WHERE a.tenant_id = ? AND a.action IN ('ORDER_INGESTED', 'PAYMENT_INGESTED')",
                w.tenant())).as("recorded by the visitor's seller, like a paste").containsExactly(w.seller().toString());
        assertThat(path(demoPost(b, "/app/demo/scenarios/happy-path/payment"))).as("used once").isEqualTo("/app/demo?n=DEMO_SEND_ORDER_FIRST#s-happy-path");
        assertThat(demoPost(b, "/app/demo/scenarios/no-such-scenario/both").status()).isEqualTo(404);
        assertThat(demoPost(b, "/app/demo/scenarios/happy-path/everything").status()).isEqualTo(404);
        DemoWorld.retire(w);
    }

    @Test
    void a_workspace_that_is_not_a_demo_has_no_scenarios_even_on_the_demo_deployment() {
        // A regular workspace forced into the demo database as its owner (provisioning refuses it there), with a password we know.
        UUID tenant = UUID.randomUUID();
        String slug = "regular-" + unique().substring(0, 8);
        String email = "seller-" + unique() + "@example.test";
        String password = newPassword();
        DemoWorld.column(DemoWorld.DB.migrateDataSource(), "INSERT INTO tenant(id, name, currency, scale, slug) VALUES (?, 'Regular', 'XAF', 0, ?)", tenant, slug);
        DemoWorld.column(DemoWorld.DB.migrateDataSource(), "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (gen_random_uuid(), ?, ?, ?, 'SELLER')",
                tenant, email, PasswordHashing.create().encode(password));
        Browser b = signedIn(slug, email, password);
        assertThat(b.get("/app/demo").status()).isEqualTo(404);
        assertThat(demoPost(b, "/app/demo/scenarios/happy-path/both").status()).isEqualTo(404);
        assertThat(b.get("/app").body()).doesNotContain("Demo, made-up data").doesNotContain("/app/demo");
        assertThat(demoOne("SELECT count(*)::text FROM orders WHERE tenant_id = ?", tenant)).isEqualTo("0");
        DemoWorld.column(DemoWorld.DB.migrateDataSource(), "UPDATE tenant SET disabled_at = now() WHERE id = ?", tenant);
    }
}
