package dev.chatledger.security;

import dev.chatledger.demo.ops.DemoWorld;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scenario step is one of the user's actions (task 9): it counts against the per-user action limit like every other POST, here two
 * in ten minutes, then 429.
 *
 * <p>Requirement 9.2. Task 9.
 */
@TestPropertySource(properties = {"app.demo.page-per-address=1000000/1m", "app.demo.claim-per-address=1000000/1m",
        "app.demo.claim-total=1000000/1m", "app.demo.scenario-per-tenant=1000000/1m", "app.security.limits.actions-per-user=2/10m"})
class DemoActionLimitIT extends AbstractDemoWebIT {

    @Test
    void a_step_counts_as_an_action_of_its_user() {
        DemoWorld.retireEverythingWaiting();
        DemoWorld.Workspace w = DemoWorld.seeded();
        Browser b = entered();
        assertThat(demoPost(b, "/app/demo/scenarios/happy-path/order").status()).isEqualTo(302);
        assertThat(demoPost(b, "/app/demo/scenarios/underpayment/order").status()).isEqualTo(302);
        Browser.Response third = demoPost(b, "/app/demo/scenarios/overpayment/order");
        assertThat(third.status()).isEqualTo(429);
        assertThat(third.header("retry-after")).isNotNull();
        DemoWorld.retire(w);
    }
}
