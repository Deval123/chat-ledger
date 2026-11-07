package dev.chatledger.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The service re-checks its input; it does not trust its caller. Requirement: 9.2. Task 4.3. */
class InputChecksTest {

    @Test
    void ordinary_input_passes() {
        assertThat(InputChecks.check("msg-2024.01:abc_9", "Name: Ana\n1 x rice @ 500\r\n\tdone")).isEmpty();
        assertThat(InputChecks.check("a".repeat(128), "x")).isEmpty();
        assertThat(InputChecks.check("A", "caf\u00E9 \uD83D\uDE00 \u0661")).isEmpty();
    }

    @Test
    void external_ids_outside_the_allowed_set_are_refused() {
        for (String bad : new String[]{null, "", "a".repeat(129), "a b", "a/b", "a;b", "a'b", "a\"b", "a%20b", "a\nb", "a\u0000b",
                "caf\u00E9", "\u0661", "a\u202Eb", "../x", "<script>", "a,b", "a@b", " a", "a "}) {
            assertThat(InputChecks.check(bad, "x")).as(String.valueOf(bad)).contains(RejectReason.INVALID_EXTERNAL_ID);
        }
    }

    @Test
    void blank_or_oversized_text_is_refused() {
        assertThat(InputChecks.check("id", null)).contains(RejectReason.BLANK_TEXT);
        assertThat(InputChecks.check("id", "")).contains(RejectReason.BLANK_TEXT);
        assertThat(InputChecks.check("id", " \n\t ")).contains(RejectReason.BLANK_TEXT);
        assertThat(InputChecks.check("id", "a".repeat(4000))).isEmpty();
        assertThat(InputChecks.check("id", "a".repeat(4001))).contains(RejectReason.TEXT_TOO_LARGE);
    }

    @Test
    void nul_control_characters_and_lone_surrogates_are_refused() {
        for (String bad : new String[]{"a\u0000b", "a\u0007b", "a\u001Bb", "a\u007Fb", "a\u0085b", "a\uD800b", "a\uDC00b", "a\uD800",
                "\uDC00\uD800"}) {
            assertThat(InputChecks.check("id", bad)).as(bad.length() + " chars").contains(RejectReason.INVALID_CHARACTERS);
        }
        assertThat(InputChecks.check("id", "\uD83D\uDE00")).as("a valid pair").isEmpty();
    }
}
