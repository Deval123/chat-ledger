package dev.chatledger.security;

import dev.chatledger.demo.ops.DemoWorld;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One demo workspace takes at most the configured number of scenario steps (task 9, D13): here three in ten minutes, then 429 with
 * {@code Retry-After}, and the refused step writes nothing.
 *
 * <p>Requirements 7, 9.2. Task 9.
 */
@TestPropertySource(properties = {"app.demo.page-per-address=1000000/1m", "app.demo.claim-per-address=1000000/1m",
        "app.demo.claim-total=1000000/1m", "app.demo.scenario-per-tenant=3/10m"})
class DemoStepLimitIT extends AbstractDemoWebIT {

    @Test
    void a_workspace_takes_three_scenario_steps_in_ten_minutes_here() {
        DemoWorld.retireEverythingWaiting();
        DemoWorld.Workspace w = DemoWorld.seeded();
        Browser b = entered();
        for (int i = 0; i < 3; i++) {
            assertThat(demoPost(b, "/app/demo/scenarios/happy-path/order").status()).as("step " + i).isEqualTo(302);
        }
        long orders = Long.parseLong(demoOne("SELECT count(*)::text FROM orders WHERE tenant_id = ?", w.tenant()));
        Browser.Response fourth = demoPost(b, "/app/demo/scenarios/happy-path/order");
        assertThat(fourth.status()).isEqualTo(429);
        assertThat(fourth.header("retry-after")).isNotNull();
        assertThat(Long.parseLong(demoOne("SELECT count(*)::text FROM orders WHERE tenant_id = ?", w.tenant()))).isEqualTo(orders);
        DemoWorld.retire(w);
    }
}
