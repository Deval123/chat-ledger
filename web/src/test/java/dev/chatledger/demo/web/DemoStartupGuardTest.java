package dev.chatledger.demo.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The second switch (task 9, D1): the demo switched on against a regular database stops the application. No database. */
class DemoStartupGuardTest {

    private static DemoLeases mode(String mode) {
        return new DemoLeases(null) {
            @Override
            public String mode() {
                return mode;
            }
        };
    }

    @Test
    void a_demo_switched_on_against_a_regular_database_does_not_start() {
        assertThatThrownBy(() -> new DemoStartupGuard(mode("REGULAR")).requireDemoDatabase())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("needs a demo database");
        assertThatThrownBy(() -> new DemoStartupGuard(mode(null)).requireDemoDatabase()).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new DemoStartupGuard(mode("DEMO")).requireDemoDatabase()).doesNotThrowAnyException();
    }
}
