package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Money is read strictly into integer minor units; an ambiguous number is refused. Task 4. */
class AmountParserTest {

    private static long minor(String text, int scale) {
        AmountParser.Result r = AmountParser.parse(text, scale);
        assertThat(r.failure()).as(text).isNull();
        return r.amount().minor();
    }

    private static FailureReason failure(String text, int scale) {
        AmountParser.Result r = AmountParser.parse(text, scale);
        assertThat(r.amount()).as(text).isNull();
        return r.failure();
    }

    @Test
    void whole_currency_units_with_any_one_grouping_separator() {
        for (String text : new String[]{"6000", "6 000", "6,000", "6.000", "6'000"}) {
            assertThat(minor(text, 0)).as(text).isEqualTo(6000);
        }
        assertThat(minor("1 234 567", 0)).isEqualTo(1_234_567);
        assertThat(minor("60,000", 0)).isEqualTo(60_000);
        assertThat(minor("1", 0)).isEqualTo(1);
    }

    @Test
    void irregular_grouping_is_refused_not_guessed() {
        for (String text : new String[]{"6,00", "6 00", "1,2345", "1 2000", "6,000.000", "6.000,000", "1,,000", ",500", "500,", "1 000 00"}) {
            assertThat(failure(text, 0)).as(text).isEqualTo(FailureReason.INVALID_AMOUNT);
        }
    }

    @Test
    void currency_words_before_or_after_the_number() {
        assertThat(AmountParser.parse("6000 XAF", 0).amount()).isEqualTo(new AmountParser.Amount(6000, "XAF"));
        assertThat(AmountParser.parse("6 000 FCFA", 0).amount()).isEqualTo(new AmountParser.Amount(6000, "XAF"));
        assertThat(AmountParser.parse("6 000 F CFA", 0).amount()).isEqualTo(new AmountParser.Amount(6000, "XAF"));
        assertThat(AmountParser.parse("6000 fcfa", 0).amount().currency()).isEqualTo("XAF");
        assertThat(AmountParser.parse("XAF 6000", 0).amount()).isEqualTo(new AmountParser.Amount(6000, "XAF"));
        assertThat(AmountParser.parse("F CFA 6000", 0).amount().currency()).isEqualTo("XAF");
        assertThat(AmountParser.parse("6000", 0).amount().currency()).as("none stated").isNull();
        assertThat(AmountParser.parse("5 EUR", 0).amount().currency()).as("another code is kept as written").isEqualTo("EUR");
    }

    @Test
    void a_bad_currency_word_or_two_of_them_is_refused() {
        for (String text : new String[]{"6000 francs", "XAF 6000 XAF", "6000 X", "6000 CFA FCFA", "6000 \u20AC1", "FC FA 6000", "6000 12"}) {
            assertThat(failure(text, 0)).as(text).isEqualTo(FailureReason.INVALID_AMOUNT);
        }
    }

    @Test
    void decimals_need_exactly_the_scale_and_only_a_space_groups_thousands() {
        assertThat(minor("12.50", 2)).isEqualTo(1250);
        assertThat(minor("12,50", 2)).isEqualTo(1250);
        assertThat(minor("12", 2)).isEqualTo(1200);
        assertThat(minor("1 500.00", 2)).isEqualTo(150_000);
        assertThat(minor("0.50", 2)).isEqualTo(50);
        for (String text : new String[]{"1,500", "1.5", "12.505", "1.500,00", "1,500.00", ".50", "12."}) {
            assertThat(failure(text, 2)).as(text).isEqualTo(FailureReason.INVALID_AMOUNT);
        }
    }

    @Test
    void zero_leading_zeros_signs_exponents_and_letters_are_refused() {
        for (String text : new String[]{"0", "000", "0500", "-500", "+500", "1e9", "0x10", "abc", "", "  ", "5 6 7"}) {
            assertThat(AmountParser.parse(text, 0).amount()).as(text).isNull();
        }
    }

    @Test
    void too_many_digits_or_over_the_cap_is_too_large() {
        assertThat(failure("1" + "0".repeat(13), 0)).isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
        assertThat(failure("9".repeat(60), 0)).isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
        assertThat(failure("1000000000001", 0)).isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
        assertThat(minor("1000000000000", 0)).isEqualTo(OrderLimits.MAX_MINOR);
        assertThat(failure("9223372036854775807", 0)).isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
        assertThat(failure("99999999999.99", 2)).isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
        // More than 13 digits is "too large" whatever the digits are, even when leading zeros would also make it invalid.
        assertThat(failure("00000000000005", 0)).isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
        assertThat(failure("0000000000005", 0)).as("13 digits: reaches the leading-zero rule").isEqualTo(FailureReason.INVALID_AMOUNT);
    }
}
