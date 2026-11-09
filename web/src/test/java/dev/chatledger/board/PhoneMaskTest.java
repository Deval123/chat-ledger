package dev.chatledger.board;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pasted message can hold a whole phone number, written with separators, and the page must not show it. Digits are counted across single
 * separators, not only in unbroken runs. Requirement 1.4.
 */
class PhoneMaskTest {

    private final PhoneMask mask = new PhoneMask();

    private static final String B = "\u2022";

    @Test
    void the_four_formats_of_the_owner_are_masked_to_their_last_four_digits() {
        assertThat(mask.maskText("6 99 00 12 34")).isEqualTo(B + " " + B + B + " " + B + B + " 12 34");
        assertThat(mask.maskText("699.001.234")).isEqualTo(B + B + B + "." + B + B + "1.234").doesNotContain("699").doesNotContain("001");
        assertThat(mask.maskText("(+237) 699-001-234")).isEqualTo("(+" + B + B + B + ") " + B + B + B + "-" + B + B + "1-234").doesNotContain("237").doesNotContain("699");
        assertThat(mask.maskText("+999 000 000 1234")).isEqualTo("+" + B + B + B + " " + B + B + B + " " + B + B + B + " 1234");
    }

    @Test
    void the_last_four_digits_are_all_that_survive_and_only_in_place() {
        for (String number : new String[]{"6 99 00 12 34", "699.001.234", "(+237) 699-001-234", "+999 000 000 1234", "0699001234", "699 001 234"}) {
            String masked = mask.maskText("call " + number + " now");
            String digits = masked.replaceAll("\\D", "");
            assertThat(digits).as(number).hasSize(4).isEqualTo(number.replaceAll("\\D", "").substring(number.replaceAll("\\D", "").length() - 4));
            assertThat(masked).startsWith("call ").endsWith(" now");
        }
    }

    @Test
    void text_around_a_number_is_left_alone() {
        assertThat(mask.maskText("Order A-1042 for Ana, phone +999 000 000 1234, deliver at 5pm"))
                .isEqualTo("Order A-1042 for Ana, phone +" + B + B + B + " " + B + B + B + " " + B + B + B + " 1234, deliver at 5pm");
        assertThat(mask.maskText("")).isEmpty();
        assertThat(mask.maskText(null)).isEmpty();
    }

    @Test
    void shorter_groups_of_digits_are_not_numbers() {
        assertThat(mask.maskText("2 x rice at 500, total 1000")).isEqualTo("2 x rice at 500, total 1000");
        assertThat(mask.maskText("123456")).isEqualTo("123456");
        assertThat(mask.maskText("12 34 56")).isEqualTo("12 34 56");
        assertThat(mask.maskText("03/11/2026 11:02")).isEqualTo("03/11/2026 11:02");
    }

    @Test
    void seven_digits_is_the_smallest_number_and_six_is_the_largest_that_is_not() {
        assertThat(mask.maskText("1234567")).isEqualTo(B + B + B + "4567");
        assertThat(mask.maskText("1 234 567")).isEqualTo(B + " " + B + B + "4 567");
        assertThat(mask.maskText("12345678")).isEqualTo(B + B + B + B + "5678");
        assertThat(mask.maskText("123 456")).isEqualTo("123 456");
    }

    @Test
    void a_break_of_three_separator_characters_or_a_letter_ends_the_number() {
        // two groups of four digits each are not a number of eight
        assertThat(mask.maskText("1234    5678")).isEqualTo("1234    5678");
        assertThat(mask.maskText("1234x5678")).isEqualTo("1234x5678");
        // up to two separator characters in a row join the groups
        assertThat(mask.maskText("1234 - 5678")).isEqualTo(B + B + B + B + " - 5678");
        assertThat(mask.maskText("1234  5678")).isEqualTo(B + B + B + B + "  5678");
        assertThat(mask.maskText("1234   5678")).isEqualTo(B + B + B + B + "   5678");
    }

    @Test
    void digits_of_other_scripts_count_too() {
        // full-width and Arabic-Indic digits
        assertThat(mask.maskText("\uFF16\uFF19\uFF19\uFF10\uFF10\uFF11\uFF12\uFF13\uFF14")).doesNotContain("\uFF16").doesNotContain("\uFF19").endsWith("\uFF11\uFF12\uFF13\uFF14");
        assertThat(mask.maskText("\u0666\u0669\u0669\u0660\u0660\u0661\u0662\u0663\u0664")).endsWith("\u0661\u0662\u0663\u0664").doesNotContain("\u0666");
    }

    @Test
    void two_numbers_in_one_text_are_each_masked() {
        assertThat(mask.maskText("699 001 234 and 677 888 999")).isEqualTo(B + B + B + " " + B + B + "1 234 and " + B + B + B + " " + B + B + "8 999");
    }

    @Test
    void the_display_form_of_a_stored_last_four() {
        assertThat(mask.display("1234")).isEqualTo(B + B + B + B + " 1234");
        assertThat(mask.spoken("1234")).isEqualTo("phone ending 1234");
        for (String bad : new String[]{null, "", "123", "12345", "12a4", "<b>1", "1234 "}) {
            assertThat(mask.display(bad)).as(String.valueOf(bad)).isEmpty();
            assertThat(mask.spoken(bad)).isEmpty();
        }
    }

    @Test
    void each_separator_joins_the_digits_of_one_number() {
        PhoneMask mask = new PhoneMask();
        assertThat(mask.maskText("call 6 (99) 00 12 34")).isEqualTo("call \u2022 (\u2022\u2022) \u2022\u2022 12 34");
        assertThat(mask.maskText("699\u00A0001\u00A0234")).isEqualTo("\u2022\u2022\u2022\u00A0\u2022\u20221\u00A0234");
        assertThat(mask.maskText("699\u202F001\u202F234")).isEqualTo("\u2022\u2022\u2022\u202F\u2022\u20221\u202F234");
        assertThat(mask.maskText("699-001-234")).isEqualTo("\u2022\u2022\u2022-\u2022\u20221-234");
    }
}
