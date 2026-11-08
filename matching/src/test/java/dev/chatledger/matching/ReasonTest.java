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

    // ── The reader the page uses ───────────────────────────────────────────

    @Test
    void the_reader_returns_what_the_writer_wrote_for_every_code() {
        for (ReasonCode code : ReasonCode.values()) {
            Reason.Parsed parsed = Reason.fromJson(Reason.toJson(List.of(new Reason(code, 7), new Reason(ReasonCode.AMOUNT_EXACT, 50))));
            assertThat(parsed.wellFormed()).isTrue();
            assertThat(parsed.unknown()).isZero();
            assertThat(parsed.reasons()).containsExactly(new Reason(code, 7), new Reason(ReasonCode.AMOUNT_EXACT, 50));
        }
        assertThat(Reason.fromJson("[]").reasons()).isEmpty();
        assertThat(Reason.fromJson("[]").wellFormed()).isTrue();
    }

    @Test
    void a_code_it_does_not_know_is_counted_never_returned() {
        Reason.Parsed parsed = Reason.fromJson("[{\"code\":\"AMOUNT_EXACT\",\"points\":50},{\"code\":\"SOMETHING_NEW\",\"points\":9}]");
        assertThat(parsed.wellFormed()).isTrue();
        assertThat(parsed.unknown()).isEqualTo(1);
        assertThat(parsed.reasons()).containsExactly(new Reason(ReasonCode.AMOUNT_EXACT, 50));
    }

    @Test
    void anything_that_is_not_exactly_the_writers_shape_is_refused_whole() {
        String[] bad = {null, "", "[", "]", "{}", "[{}]", "[{\"code\":\"AMOUNT_EXACT\"}]", "[{\"code\":\"amount_exact\",\"points\":5}]",
                "[{\"code\":\"AMOUNT_EXACT\",\"points\":5}", "[{\"code\":\"AMOUNT_EXACT\",\"points\":5},]", "[{\"code\":\"AMOUNT_EXACT\",\"points\":5},,]",
                "[{\"code\":\"AMOUNT_EXACT\",\"points\":1000}]", "[{\"code\":\"AMOUNT_EXACT\",\"points\":101}]", "[{\"code\":\"AMOUNT_EXACT\",\"points\":-1}]",
                "[{\"code\":\"AMOUNT_EXACT\",\"points\":5,\"x\":1}]", "[ {\"code\":\"AMOUNT_EXACT\",\"points\":5}]", "[{\"code\":\"<script>\",\"points\":5}]",
                "[{\"code\":\"\",\"points\":5}]", "[{\"code\":\"AMOUNT_EXACT\",\"points\":}]", "[{\"code\":\"AMOUNT_EXACT\",\"points\":5}{\"code\":\"AMOUNT_EXACT\",\"points\":5}]",
                "x".repeat(2001)};
        for (String text : bad) {
            Reason.Parsed parsed = Reason.fromJson(text);
            assertThat(parsed.wellFormed()).as(String.valueOf(text)).isFalse();
            assertThat(parsed.reasons()).isEmpty();
        }
    }

    @Test
    void the_boundaries_of_points_are_accepted() {
        assertThat(Reason.fromJson("[{\"code\":\"AMOUNT_EXACT\",\"points\":0}]").reasons()).containsExactly(new Reason(ReasonCode.AMOUNT_EXACT, 0));
        assertThat(Reason.fromJson("[{\"code\":\"AMOUNT_EXACT\",\"points\":100}]").reasons()).containsExactly(new Reason(ReasonCode.AMOUNT_EXACT, 100));
    }
}
