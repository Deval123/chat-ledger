package dev.chatledger.matching;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The matching settings reach the matcher with their bounds enforced at startup: a value outside them stops the application. These run
 * with a bare Spring context, so they need no database.
 *
 * <p>Requirement: 3.2, 3.3, 9. Task 6.1.
 */
class MatchingPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(MatchingConfig.class);

    @Test
    void with_no_settings_the_defaults_apply_and_the_kill_switch_is_on() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            MatchConfig config = context.getBean(MatchConfig.class);
            assertThat(config).isEqualTo(MatchConfig.defaults());
            assertThat(config.autoEnabled()).isTrue();
        });
    }

    @Test
    void settings_are_read_from_the_app_matching_keys() {
        runner.withPropertyValues("app.matching.auto-threshold=90", "app.matching.suggest-threshold=50", "app.matching.auto-enabled=false",
                "app.matching.candidate-cap=50", "app.matching.tie-margin=15").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MatchConfig.class)).isEqualTo(new MatchConfig(90, 50, false, 50, 15));
        });
    }

    @Test
    void a_value_outside_its_bounds_stops_the_application() {
        for (String bad : new String[]{"app.matching.auto-threshold=74", "app.matching.auto-threshold=101", "app.matching.suggest-threshold=39",
                "app.matching.suggest-threshold=91", "app.matching.candidate-cap=9", "app.matching.candidate-cap=501",
                "app.matching.tie-margin=4", "app.matching.tie-margin=31", "app.matching.auto-threshold=oops", "app.matching.auto-enabled=maybe"}) {
            runner.withPropertyValues(bad).run(context -> assertThat(context).as(bad).hasFailed());
        }
    }

    @Test
    void the_gap_between_the_thresholds_is_enforced_when_each_is_in_bounds() {
        runner.withPropertyValues("app.matching.auto-threshold=80", "app.matching.suggest-threshold=75")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.matching.auto-threshold=85", "app.matching.suggest-threshold=75")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void a_failure_names_the_key_and_not_a_value_from_elsewhere() {
        runner.withPropertyValues("app.matching.auto-threshold=70").run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root.getMessage()).contains("app.matching.auto-threshold");
        });
    }
}
