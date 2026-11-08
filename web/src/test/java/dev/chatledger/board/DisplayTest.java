package dev.chatledger.board;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What comes from a message is cleaned for display and cut; the stored text is never touched. Requirement 9.5. */
class DisplayTest {

    private final Display display = new Display();

    @Test
    void ordinary_text_passes_unchanged_and_accents_survive() {
        assertThat(display.text("Ana Kamga", 40)).isEqualTo("Ana Kamga");
        assertThat(display.text("Éloïse N'Diaye \u00e7a va", 40)).isEqualTo("Éloïse N'Diaye ça va");
        assertThat(display.text(null, 40)).isEmpty();
        assertThat(display.text("x", 0)).isEmpty();
    }

    @Test
    void control_characters_are_removed_each_kind() {
        assertThat(display.text("a\u0000b\u0001c\u007Fd\u009Fe", 40)).isEqualTo("abcde");
        assertThat(display.text("a\u001Bb", 40)).isEqualTo("ab");
    }

    @Test
    void bidirectional_overrides_isolates_and_marks_are_removed() {
        for (String c : new String[]{"\u202A", "\u202B", "\u202C", "\u202D", "\u202E", "\u2066", "\u2067", "\u2068", "\u2069", "\u200E", "\u200F", "\u061C"}) {
            assertThat(display.text("pay" + c + "ment", 40)).as("U+%04X", (int) c.charAt(0)).isEqualTo("payment");
        }
    }

    @Test
    void zero_width_characters_the_byte_order_mark_and_the_soft_hyphen_are_removed() {
        for (String c : new String[]{"\u200B", "\u200C", "\u200D", "\u2060", "\uFEFF", "\u00AD"}) {
            assertThat(display.text("a" + c + "b", 40)).as("U+%04X", (int) c.charAt(0)).isEqualTo("ab");
        }
    }

    @Test
    void private_use_unassigned_and_lone_surrogates_are_removed() {
        assertThat(display.text("a\uE000b", 40)).isEqualTo("ab");
        assertThat(display.text("a\uD800b", 40)).as("lone high surrogate").isEqualTo("ab");
        assertThat(display.text("a\uDC00b", 40)).as("lone low surrogate").isEqualTo("ab");
        assertThat(display.text("a\u0378b", 40)).as("unassigned").isEqualTo("ab");
    }

    @Test
    void a_valid_surrogate_pair_is_kept_and_never_cut_in_half() {
        String emoji = "\uD83D\uDE00";
        assertThat(display.text("a" + emoji + "b", 40)).isEqualTo("a" + emoji + "b");
        String cut = display.text(emoji + emoji + emoji, 2);
        assertThat(cut).isEqualTo(emoji + emoji + "\u2026");
        assertThat(cut.codePoints().noneMatch(cp -> Character.getType(cp) == Character.SURROGATE)).isTrue();
    }

    @Test
    void all_white_space_becomes_one_space_and_the_ends_are_trimmed() {
        assertThat(display.text("  a \t b\r\n c\u00A0\u2003d\u2028e\u2029f\u0085g  ", 40)).isEqualTo("a b c d e f g");
        assertThat(display.text("\n\n", 40)).isEmpty();
    }

    @Test
    void stacked_combining_marks_are_limited_to_two_in_a_row() {
        String zalgo = "a" + "\u0301".repeat(30) + "b";
        assertThat(display.text(zalgo, 40)).isEqualTo("a\u0301\u0301b");
        assertThat(display.text("e\u0301", 40)).as("one mark is normal").isEqualTo("e\u0301");
    }

    @Test
    void the_cut_is_by_code_points_with_an_ellipsis_and_the_boundary_is_exact() {
        String s = "abcdefghij";
        assertThat(display.text(s, 10)).isEqualTo(s);
        assertThat(display.text(s, 9)).isEqualTo("abcdefghi\u2026");
        assertThat(display.text(s, 1)).isEqualTo("a\u2026");
        assertThat(display.text("abc def", 4)).as("no space left before the ellipsis").isEqualTo("abc\u2026");
        assertThat(display.text("x".repeat(5000), 100)).hasSize(101);
    }

    @Test
    void markup_and_template_syntax_are_not_touched_here_because_the_template_escapes_them() {
        assertThat(display.text("<script>alert(1)</script>", 80)).isEqualTo("<script>alert(1)</script>");
        assertThat(display.text("${7*7} [[${x}]] th:text", 80)).isEqualTo("${7*7} [[${x}]] th:text");
    }

    @Test
    void a_block_keeps_line_breaks_but_never_more_than_one_blank_line() {
        assertThat(display.block("line one\r\nline two\n\n\n\nline three", 200)).isEqualTo("line one\nline two\n\nline three");
        assertThat(display.block("a \t b\n\u202Ec", 200)).isEqualTo("a b\nc");
        assertThat(display.block("\n\nstart", 200)).isEqualTo("start");
        assertThat(display.block("x".repeat(400), 100)).hasSize(101);
    }

    @Test
    void a_tab_is_white_space_and_becomes_one_space_not_nothing() {
        assertThat(new Display().text("a\tb", 10)).isEqualTo("a b");
        assertThat(new Display().block("a\t\tb", 10)).isEqualTo("a b");
    }
}
