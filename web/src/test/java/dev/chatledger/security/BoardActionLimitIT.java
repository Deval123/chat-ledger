package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every state-changing route counts against the per-user action limit. The limit is lowered to 2 a minute here; the third call to each route,
 * from a user who has not used up anything else, is refused with 429 before the service runs.
 *
 * <p>Requirement: 9.1 (rate limits). Task 8.
 */
@TestPropertySource(properties = {"app.security.limits.actions-per-user=2/1m", "app.export.max-rows=3"})   // shares its context with BoardExportCapIT
class BoardActionLimitIT extends BoardIT {

    private static final List<String> ROUTES = List.of("/app/orders", "/app/payments", "/app/matches/{id}/confirm", "/app/matches/{id}/reject",
            "/app/matches/manual", "/app/matches/{id}/unmatch", "/app/payments/{id}/set-aside", "/app/payments/{id}/take-back", "/app/rematch",
            "/app/orders/{id}/approve", "/app/books/check", "/app/flags/{id}/resolve");

    @Test
    void the_third_action_in_a_minute_is_refused_on_every_route() {
        for (String route : ROUTES) {
            Workspace w = newWorkspace();
            Browser seller = signedIn(w.slug(), w.email(), w.password());
            String token = Browser.csrf(seller.get("/app"));
            List<Integer> statuses = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("_csrf", token);
                form.put("mid", UUID.randomUUID().toString());
                form.put("text", "Order: L-" + i + "\nName: Limit\n1 x rice @ 100");
                form.put("orderId", UUID.randomUUID().toString());
                form.put("paymentId", UUID.randomUUID().toString());
                form.put("total", "100");
                statuses.add(seller.post(route.replace("{id}", UUID.randomUUID().toString()), form).status());
            }
            assertThat(statuses.subList(0, 2)).as(route).doesNotContain(429);
            assertThat(statuses.get(2)).as(route + ": third call").isEqualTo(429);
        }
    }
}
