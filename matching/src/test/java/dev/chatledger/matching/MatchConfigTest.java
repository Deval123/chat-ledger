package dev.chatledger.matching;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Thresholds are configuration with hard bounds: a value outside them stops the application instead of weakening the defense.
 *
 * <p>Requirement: 3.2, 3.3, 9. Task 6.1.
 */
class MatchConfigTest {

    @Test
    void the_defaults_are_the_design_values_and_the_kill_switch_is_on() {
        MatchConfig c = MatchConfig.defaults();
        assertThat(c.autoThreshold()).isEqualTo(85);
        assertThat(c.suggestThreshold()).isEqualTo(60);
        assertThat(c.autoEnabled()).isTrue();
        assertThat(c.candidateCap()).isEqualTo(200);
        assertThat(c.tieMargin()).isEqualTo(10);
    }

    @Test
    void the_automatic_threshold_is_between_75_and_100() {
        assertThatCode(() -> new MatchConfig(75, 60, true, 200, 10)).doesNotThrowAnyException();
        assertThatCode(() -> new MatchConfig(100, 60, true, 200, 10)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new MatchConfig(74, 60, true, 200, 10)).hasMessageContaining("auto-threshold");
        assertThatThrownBy(() -> new MatchConfig(101, 60, true, 200, 10)).hasMessageContaining("auto-threshold");
    }

    @Test
    void the_suggestion_threshold_is_between_40_and_90() {
        assertThatCode(() -> new MatchConfig(100, 40, true, 200, 10)).doesNotThrowAnyException();
        assertThatCode(() -> new MatchConfig(100, 90, true, 200, 10)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new MatchConfig(100, 39, true, 200, 10)).hasMessageContaining("suggest-threshold");
        assertThatThrownBy(() -> new MatchConfig(100, 91, true, 200, 10)).as("the automatic threshold cannot exceed 100").hasMessageContaining("at least");
    }

    @Test
    void the_automatic_threshold_must_beat_the_suggestion_threshold_by_ten() {
        assertThatCode(() -> new MatchConfig(85, 75, true, 200, 10)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new MatchConfig(85, 76, true, 200, 10)).hasMessageContaining("at least");
        assertThatThrownBy(() -> new MatchConfig(80, 75, true, 200, 10)).hasMessageContaining("at least");
    }

    @Test
    void the_candidate_cap_is_between_10_and_500() {
        assertThatCode(() -> new MatchConfig(85, 60, true, 10, 10)).doesNotThrowAnyException();
        assertThatCode(() -> new MatchConfig(85, 60, true, 500, 10)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new MatchConfig(85, 60, true, 9, 10)).hasMessageContaining("candidate-cap");
        assertThatThrownBy(() -> new MatchConfig(85, 60, true, 501, 10)).hasMessageContaining("candidate-cap");
    }

    @Test
    void the_tie_margin_is_between_5_and_30() {
        assertThatCode(() -> new MatchConfig(85, 60, true, 200, 5)).doesNotThrowAnyException();
        assertThatCode(() -> new MatchConfig(85, 60, true, 200, 30)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new MatchConfig(85, 60, true, 200, 4)).hasMessageContaining("tie-margin");
        assertThatThrownBy(() -> new MatchConfig(85, 60, true, 200, 31)).hasMessageContaining("tie-margin");
    }
}
