package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authorization matrix of the board routes. The table below says, for every route, the permission it needs and what a seller gets for an
 * id that does not exist. A second test reads the real request mappings and fails if a route is missing from the table (or the table names a
 * route that does not exist, or a permission that differs from the annotation), so a new route cannot be forgotten.
 *
 * <p>For every route: anonymous is sent to sign-in (or refused), a user without the permission gets 403 (the CSRF token is valid, so the
 * refusal is the permission and not the token), a POST without or with a wrong token gets 403, an unknown id gets 404, and a seller of
 * another workspace gets 404 for the ids of this one and changes nothing.
 *
 * <p>Requirement: 9.1. Task 8.
 */
class BoardRoutesIT extends BoardIT {

    private record Route(String method, String pattern, String authority, Map<String, String> form, int unknownIdStatus) {
    }

    private static final String ANY_ID = "{id}";

    private static final List<Route> ROUTES = List.of(
            get("/app", "VIEW_BOARD", 200),
            get("/app/columns/{column}", "VIEW_BOARD", 200),
            get("/app/orders/{id}", "VIEW_BOARD", 404),
            get("/app/payments/{id}", "VIEW_BOARD", 404),
            get("/app/orders/{id}/review", "APPROVE_ORDER_TOTAL", 404),
            get("/app/payments/{id}/match", "CONFIRM_OR_OVERRIDE_MATCH", 404),
            get("/app/matches/{id}/undo", "REVERSE_TRANSACTION", 404),
            get("/app/summary", "VIEW_BOARD", 200),
            get("/app/books", "VIEW_LEDGER", 200),
            get("/app/export", "EXPORT_DATA", 200),
            get("/app/export/orders.csv", "EXPORT_DATA", 200),
            get("/app/export/payments.csv", "EXPORT_DATA", 200),
            get("/app/export/ledger.csv", "EXPORT_DATA and VIEW_LEDGER", 200),
            post("/app/orders", "RECORD_ORDER", Map.of("text", "Order: Z-1\nName: Zed\n1 x rice @ 100"), 302),
            post("/app/payments", "RECORD_PAYMENT", Map.of("text", "nothing readable here"), 302),
            post("/app/matches/{id}/confirm", "CONFIRM_OR_OVERRIDE_MATCH", Map.of(), 404),
            post("/app/matches/{id}/reject", "CONFIRM_OR_OVERRIDE_MATCH", Map.of(), 404),
            post("/app/matches/manual", "CONFIRM_OR_OVERRIDE_MATCH", Map.of("orderId", "{id}", "paymentId", "{id}"), 404),
            post("/app/matches/{id}/unmatch", "REVERSE_TRANSACTION", Map.of(), 404),
            post("/app/payments/{id}/set-aside", "CONFIRM_OR_OVERRIDE_MATCH", Map.of(), 404),
            post("/app/payments/{id}/take-back", "CONFIRM_OR_OVERRIDE_MATCH", Map.of(), 404),
            post("/app/rematch", "CONFIRM_OR_OVERRIDE_MATCH", Map.of(), 302),
            post("/app/orders/{id}/approve", "APPROVE_ORDER_TOTAL", Map.of("total", "100", "confirm", "yes"), 404),
            post("/app/books/check", "VIEW_LEDGER", Map.of(), 302),
            post("/app/flags/{id}/resolve", "RESOLVE_FLAG", Map.of(), 404));

    private static Route get(String pattern, String authority, int unknown) {
        return new Route("GET", pattern, authority, Map.of(), unknown);
    }

    private static Route post(String pattern, String authority, Map<String, String> form, int unknown) {
        return new Route("POST", pattern, authority, form, unknown);
    }

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private static String filled(String pattern, String id) {
        return pattern.replace("{id}", id).replace("{column}", "awaiting");
    }

    private Browser.Response call(Browser b, Route r, String id, boolean withToken) {
        String path = filled(r.pattern, id);
        if (r.method.equals("GET")) {
            return b.get(path);
        }
        Map<String, String> form = new LinkedHashMap<>();
        r.form.forEach((k, v) -> form.put(k, filled(v, id)));
        form.put("mid", UUID.randomUUID().toString());
        if (withToken) {
            form.put("_csrf", Browser.csrf(b.get("/app")));
        }
        return b.post(path, form);
    }

    private static boolean allowed(Role role, String authority) {
        for (String part : authority.split(" and ")) {
            if (!role.allows(Permission.valueOf(part.trim()))) {
                return false;
            }
        }
        return true;
    }

    @Test
    void the_table_names_every_board_route_and_the_permission_of_its_annotation() {
        Set<String> found = new TreeSet<>();
        Map<String, String> annotated = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            if (!e.getValue().getBeanType().getPackageName().equals("dev.chatledger.board")) {
                continue;
            }
            PreAuthorize pre = e.getValue().getMethodAnnotation(PreAuthorize.class);
            assertThat(pre).as(e.getKey() + " has @PreAuthorize").isNotNull();
            List<String> authorities = new ArrayList<>();
            Matcher m = Pattern.compile("hasAuthority\\('([A-Z_]+)'\\)").matcher(pre.value());
            while (m.find()) {
                authorities.add(m.group(1));
            }
            assertThat(authorities).as(e.getKey() + " names its permission").isNotEmpty();
            for (String method : e.getKey().getMethodsCondition().getMethods().stream().map(Enum::name).toList()) {
                for (String pattern : e.getKey().getPathPatternsCondition().getPatternValues()) {
                    found.add(method + " " + pattern);
                    annotated.put(method + " " + pattern, String.join(" and ", authorities));
                }
            }
        }
        Set<String> tabled = new TreeSet<>();
        for (Route r : ROUTES) {
            tabled.add(r.method + " " + r.pattern);
            assertThat(annotated.get(r.method + " " + r.pattern)).as(r.pattern + ": the annotation and the table agree").isEqualTo(r.authority);
        }
        assertThat(found).as("every board route is in the table").isEqualTo(tabled);
        for (String route : found) {
            assertThat(route).as("a route changes state only by POST").matches("(GET|POST) .*");
        }
    }

    @Test
    void anonymous_visitors_are_sent_to_sign_in_and_cannot_post() {
        for (Route r : ROUTES) {
            Browser.Response response = call(browser(), r, UUID.randomUUID().toString(), false);
            if (r.method.equals("GET")) {
                assertThat(response.status()).as(r.pattern).isEqualTo(302);
                assertThat(response.header("location")).as(r.pattern).endsWith("/login");
            } else {
                assertThat(response.status()).as(r.pattern).isIn(302, 403);
            }
            assertThat(response.body()).as(r.pattern).doesNotContain("Test shop");
        }
    }

    @Test
    void a_helper_gets_403_exactly_where_the_role_table_withholds_the_permission() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser b = signedIn(w.slug(), helper.email(), helper.password());
        for (Route r : ROUTES) {
            Browser.Response response = call(b, r, UUID.randomUUID().toString(), true);
            if (allowed(Role.HELPER, r.authority)) {
                assertThat(response.status()).as("helper " + r.method + " " + r.pattern).isNotEqualTo(403);
            } else {
                assertThat(response.status()).as("helper " + r.method + " " + r.pattern).isEqualTo(403);
                assertThat(response.body()).as(r.pattern).doesNotContain("Test shop").doesNotContain("XAF");
            }
        }
    }

    @Test
    void a_post_needs_the_csrf_token_and_changes_nothing_without_it() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        String before = one("SELECT (SELECT count(*) FROM orders WHERE tenant_id = ?)::text || '/' || (SELECT count(*) FROM audit_log WHERE tenant_id = ?)::text",
                w.tenantId(), w.tenantId());
        String token = Browser.csrf(b.get("/app"));
        for (Route r : ROUTES) {
            if (!r.method.equals("POST")) {
                continue;
            }
            Browser.Response none = call(b, r, UUID.randomUUID().toString(), false);
            assertThat(none.status()).as(r.pattern + " without a token").isEqualTo(403);
            Map<String, String> form = new LinkedHashMap<>(r.form);
            form.replaceAll((k, v) -> filled(v, UUID.randomUUID().toString()));
            form.put("mid", UUID.randomUUID().toString());
            form.put("_csrf", token + "x");
            assertThat(b.post(filled(r.pattern, UUID.randomUUID().toString()), form).status()).as(r.pattern + " with a wrong token").isEqualTo(403);
            assertThat(b.post(filled(r.pattern, UUID.randomUUID().toString()), Map.of("mid", UUID.randomUUID().toString()),
                    "HX-Request", "true", "X-CSRF-TOKEN", "wrong").status()).as(r.pattern + " htmx with a wrong token").isEqualTo(403);
        }
        assertThat(one("SELECT (SELECT count(*) FROM orders WHERE tenant_id = ?)::text || '/' || (SELECT count(*) FROM audit_log WHERE tenant_id = ?)::text",
                w.tenantId(), w.tenantId())).isEqualTo(before);
    }

    @Test
    void an_unknown_id_is_a_404_for_a_seller_on_every_route_and_a_bad_id_is_a_400() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        for (Route r : ROUTES) {
            Browser.Response response = call(b, r, UUID.randomUUID().toString(), true);
            assertThat(response.status()).as("seller " + r.method + " " + r.pattern).isEqualTo(r.unknownIdStatus);
            if (r.pattern.contains(ANY_ID)) {
                Browser.Response bad = call(b, r, "not-a-uuid", true);
                assertThat(bad.status()).as(r.pattern + " with a bad id").isEqualTo(400);
                assertThat(bad.body()).doesNotContain("not-a-uuid");
            }
        }
        assertThat(b.get("/app/columns/nonsense").status()).isEqualTo(400);
        assertThat(b.get("/app/columns/awaiting?after=junk").status()).isEqualTo(400);
        assertThat(b.get("/app/columns/awaiting?page=6").status()).isEqualTo(400);
        assertThat(b.get("/app/columns/awaiting?page=0").status()).isEqualTo(400);
    }

    @Test
    void a_seller_of_another_workspace_gets_404_for_the_ids_of_this_one_and_changes_nothing() {
        Workspace a = newWorkspace();
        Member helper = addUser(a, Role.HELPER);
        Browser sellerA = signedIn(a.slug(), a.email(), a.password());
        Browser helperA = signedIn(a.slug(), helper.email(), helper.password());
        pasteOrder(sellerA, orderText("X-1", "Xan Mba", "+999 000 000 1234", 3000));
        pasteOrder(sellerA, "Order: X-2\nName: Yan\n2 x mystery basket");
        pastePayment(helperA, sms(3000, "Xan Mba", "+999 000 000 1234", "X-1"));
        UUID order = orderId(a.tenantId(), "X-1");
        UUID review = orderId(a.tenantId(), "X-2");
        UUID payment = UUID.fromString(one("SELECT id::text FROM payment WHERE tenant_id = ?", a.tenantId()));
        UUID suggestion = UUID.fromString(one("SELECT id::text FROM match WHERE tenant_id = ? AND state = 'SUGGESTED'", a.tenantId()));
        String before = one("SELECT string_agg(status, ',' ORDER BY status) FROM orders WHERE tenant_id = ?", a.tenantId())
                + "|" + one("SELECT count(*)::text FROM audit_log WHERE tenant_id = ?", a.tenantId());

        Workspace other = newWorkspace();
        Browser sellerB = signedIn(other.slug(), other.email(), other.password());
        List<String> paths = List.of("/app/orders/" + order, "/app/payments/" + payment, "/app/orders/" + review + "/review",
                "/app/payments/" + payment + "/match", "/app/matches/" + suggestion + "/undo");
        for (String path : paths) {
            Browser.Response r = sellerB.get(path);
            assertThat(r.status()).as(path).isEqualTo(404);
            assertThat(r.body()).as(path).doesNotContain("Xan Mba").doesNotContain("X-1").doesNotContain("mystery");
        }
        List<String> posts = List.of("/app/matches/" + suggestion + "/confirm", "/app/matches/" + suggestion + "/reject",
                "/app/matches/" + suggestion + "/unmatch", "/app/payments/" + payment + "/set-aside", "/app/payments/" + payment + "/take-back",
                "/app/orders/" + review + "/approve");
        for (String path : posts) {
            assertThat(post(sellerB, path, Map.of("total", "999")).status()).as(path).isEqualTo(404);
            assertThat(post(sellerB, path, Map.of("total", "999", "confirm", "yes")).status()).as(path + " confirmed").isEqualTo(404);
        }
        assertThat(post(sellerB, "/app/matches/manual", Map.of("orderId", order.toString(), "paymentId", payment.toString())).status()).isEqualTo(404);
        assertThat(one("SELECT string_agg(status, ',' ORDER BY status) FROM orders WHERE tenant_id = ?", a.tenantId())
                + "|" + one("SELECT count(*)::text FROM audit_log WHERE tenant_id = ?", a.tenantId())).isEqualTo(before);
        assertThat(one("SELECT state FROM match WHERE id = ?", suggestion)).isEqualTo("SUGGESTED");
        // The foreign tenant's board shows nothing of this workspace.
        assertThat(sellerB.get("/app").body()).doesNotContain("Xan Mba").doesNotContain("X-1");
        // A pair that crosses workspaces is refused the same way: a payment of A and an order of B.
        pasteOrder(sellerB, orderText("Y-1", "Yvo Fon", "+999 000 000 5555", 3000));
        UUID orderB = orderId(other.tenantId(), "Y-1");
        assertThat(post(sellerB, "/app/matches/manual", Map.of("orderId", orderB.toString(), "paymentId", payment.toString())).status()).isEqualTo(404);
    }
}
