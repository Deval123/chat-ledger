package dev.chatledger.matching;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reasons are codes and integers; the JSON is built from nothing else, so no text from a message can reach {@code match.reasons_json}. */
class ReasonTest {

    @Test
    void the_json_holds_enum_names_and_integers_only() {
        String json = Reason.toJson(List.of(new Reason(ReasonCode.AMOUNT_EXACT, 50), new Reason(ReasonCode.REFERENCE_AMBIGUOUS, 0)));
        assertThat(json).isEqualTo("[{\"code\":\"AMOUNT_EXACT\",\"points\":50},{\"code\":\"REFERENCE_AMBIGUOUS\",\"points\":0}]");
        assertThat(Reason.toJson(List.of())).isEqualTo("[]");
    }

    @Test
    void every_code_fits_the_database_character_rule() {
        // the same character set as the CHECK on match.reasons_json (V016)
        for (ReasonCode code : ReasonCode.values()) {
            String json = Reason.toJson(List.of(new Reason(code, 100)));
            assertThat(json).matches("[A-Za-z0-9_:,\"{}\\[\\] ]*");
        }
    }

    @Test
    void points_are_bounded() {
        assertThatThrownBy(() -> new Reason(ReasonCode.AMOUNT_EXACT, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Reason(ReasonCode.AMOUNT_EXACT, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Reason(null, 1)).isInstanceOf(NullPointerException.class);
    }
}
