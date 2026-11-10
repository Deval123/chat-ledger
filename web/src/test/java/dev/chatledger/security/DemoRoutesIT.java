package dev.chatledger.security;

import dev.chatledger.demo.ops.DemoWorld;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every route the demo adds, in a table checked against the running application's own mappings (task 9): who may call it, that it
 * changes state by POST only and with a CSRF token, that an anonymous visitor is sent to the demo page (htmx gets 401 with
 * {@code HX-Redirect}), and that nothing is written by a refused request.
 *
 * <p>Requirements 7, 9.1, 9.5. Task 9.
 */
class DemoRoutesIT extends AbstractDemoWebIT {

    /** The demo's routes and what each one requires; "public" means the permit list and no principal. */
    private static final Map<String, String> ROUTES = new TreeMap<>(Map.of(
            "GET /demo", "public",
            "POST /demo", "public",
            "GET /app/demo", "VIEW_BOARD",
            "POST /app/demo/scenarios/{scenario}/{step}", "RECORD_ORDER and RECORD_PAYMENT"));

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void the_table_names_every_demo_route_and_its_permission() {
        Map<String, String> found = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            String type = e.getValue().getBeanType().getSimpleName();
            if (!type.startsWith("Demo")) {
                continue;
            }
            PreAuthorize pre = e.getValue().getMethodAnnotation(PreAuthorize.class);
            String permission = pre == null ? "public" : pre.value().replace("hasAuthority('", "").replace("')", "");
            for (var method : e.getKey().getMethodsCondition().getMethods()) {
                for (String pattern : e.getKey().getPathPatternsCondition().getPatternValues()) {
                    found.put(method.name() + " " + pattern, permission);
                }
            }
        }
        assertThat(found).isEqualTo(ROUTES);
    }

    @Test
    void an_anonymous_visitor_is_sent_to_the_demo_page_and_cannot_post_a_step() {
        Browser anonymous = browser();
        Browser.Response page = anonymous.get("/app/demo");
        assertThat(page.status()).isEqualTo(302);
        assertThat(path(page)).isEqualTo("/demo");
        Browser.Response htmx = anonymous.get("/app/demo", "HX-Request", "true");
        assertThat(htmx.status()).isEqualTo(401);
        assertThat(htmx.header("hx-redirect")).isEqualTo("/demo");
        long orders = Long.parseLong(demoOne("SELECT count(*)::text FROM orders"));
        Browser.Response post = anonymous.post("/app/demo/scenarios/happy-path/both", Map.of());
        assertThat(post.status()).isIn(302, 403);
        assertThat(Long.parseLong(demoOne("SELECT count(*)::text FROM orders"))).isEqualTo(orders);
    }

    @Test
    void a_step_needs_its_csrf_token_and_changes_state_only_by_post() {
        DemoWorld.retireEverythingWaiting();
        DemoWorld.Workspace w = DemoWorld.seeded();
        Browser b = entered();
        String before = demoOne("SELECT count(*)::text FROM orders WHERE tenant_id = ?", w.tenant());
        assertThat(b.post("/app/demo/scenarios/happy-path/both", Map.of()).status()).isEqualTo(403);
        assertThat(b.post("/app/demo/scenarios/happy-path/both", Map.of("_csrf", Browser.csrf(b.get("/app")) + "x")).status()).isEqualTo(403);
        assertThat(b.get("/app/demo/scenarios/happy-path/both").status()).as("no GET writes").isIn(404, 405);
        assertThat(demoOne("SELECT count(*)::text FROM orders WHERE tenant_id = ?", w.tenant())).isEqualTo(before);
        assertThat(demoPost(b, "/app/demo/scenarios/happy-path/both").status()).isEqualTo(302);
        assertThat(demoOne("SELECT count(*)::text FROM orders WHERE tenant_id = ?", w.tenant())).isNotEqualTo(before);
        DemoWorld.retire(w);
    }
}
