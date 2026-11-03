package dev.chatledger.matching;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke test for the matching module: verifies threshold classification logic.
 *
 * <p>Requirement: 3 (Match orders and payments).
 */
class MatchScoreSmokeTest {

    @Test
    void score_above_auto_threshold_is_auto_match() {
        var score = new MatchScore(90, List.of("Amount matches", "Reference code matches"));
        assertThat(score.isAutoMatch()).isTrue();
        assertThat(score.isSuggestion()).isFalse();
        assertThat(score.isUnmatched()).isFalse();
    }

    @Test
    void score_in_suggestion_band_is_suggestion() {
        var score = new MatchScore(70, List.of("Amount matches"));
        assertThat(score.isAutoMatch()).isFalse();
        assertThat(score.isSuggestion()).isTrue();
    }

    @Test
    void low_score_is_unmatched() {
        var score = new MatchScore(30, List.of());
        assertThat(score.isUnmatched()).isTrue();
    }

    @Test
    void out_of_range_score_is_rejected() {
        assertThatThrownBy(() -> new MatchScore(101, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
