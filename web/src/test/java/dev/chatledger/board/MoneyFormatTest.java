package dev.chatledger.board;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Money is an integer; it is formatted and read back by integer arithmetic only. Requirement 4.4. */
class MoneyFormatTest {

    private final MoneyFormat money = new MoneyFormat();

    @Test
    void whole_currencies_are_grouped_by_thousands() {
        assertThat(money.format(0, 0, "XAF")).isEqualTo("0 XAF");
        assertThat(money.format(999, 0, "XAF")).isEqualTo("999 XAF");
        assertThat(money.format(1000, 0, "XAF")).isEqualTo("1,000 XAF");
        assertThat(money.format(5000, 0, "XAF")).isEqualTo("5,000 XAF");
        assertThat(money.format(1_000_000_000_000L, 0, "XAF")).isEqualTo("1,000,000,000,000 XAF");
        assertThat(money.format(-1500, 0, "XAF")).isEqualTo("-1,500 XAF");
    }

    @Test
    void currencies_with_decimals_keep_every_digit() {
        assertThat(money.format(5025, 2, "EUR")).isEqualTo("50.25 EUR");
        assertThat(money.format(5, 2, "EUR")).isEqualTo("0.05 EUR");
        assertThat(money.format(0, 2, "EUR")).isEqualTo("0.00 EUR");
        assertThat(money.format(100000000, 2, "EUR")).isEqualTo("1,000,000.00 EUR");
        assertThat(money.format(-305, 2, "EUR")).isEqualTo("-3.05 EUR");
        assertThat(money.format(123456, 3, "KWD")).isEqualTo("123.456 KWD");
    }

    @Test
    void the_largest_and_smallest_longs_do_not_overflow() {
        assertThat(MoneyFormat.plain(Long.MAX_VALUE, 0)).isEqualTo("9223372036854775807");
        assertThat(MoneyFormat.plain(Long.MIN_VALUE, 0)).isEqualTo("-9223372036854775808");
        assertThat(MoneyFormat.plain(Long.MAX_VALUE, 2)).isEqualTo("92233720368547758.07");
    }

    @Test
    void a_currency_that_is_not_three_capital_letters_is_left_out() {
        for (String bad : new String[]{null, "", "xaf", "XA", "XAFF", "<b>", "X1F"}) {
            assertThat(money.format(5, 0, bad)).as(String.valueOf(bad)).isEqualTo("5");
        }
    }

    @Test
    void a_scale_out_of_range_is_refused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MoneyFormat.plain(5, -1)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MoneyFormat.plain(5, 7)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void typed_amounts_are_read_strictly() {
        assertThat(MoneyFormat.parse("5000", 0)).isEqualTo(OptionalLong.of(5000));
        assertThat(MoneyFormat.parse(" 5000 ", 0)).isEqualTo(OptionalLong.of(5000));
        assertThat(MoneyFormat.parse("5000\r\n", 0)).as("the ends are trimmed, the middle is not").isEqualTo(OptionalLong.of(5000));
        assertThat(MoneyFormat.parse("50.25", 2)).isEqualTo(OptionalLong.of(5025));
        assertThat(MoneyFormat.parse("50.2", 2)).isEqualTo(OptionalLong.of(5020));
        assertThat(MoneyFormat.parse("50", 2)).isEqualTo(OptionalLong.of(5000));
        assertThat(MoneyFormat.parse("0.05", 2)).isEqualTo(OptionalLong.of(5));
        assertThat(MoneyFormat.parse("007", 0)).isEqualTo(OptionalLong.of(7));
        assertThat(MoneyFormat.parse("999999999999999", 0)).isEqualTo(OptionalLong.of(999_999_999_999_999L));
    }

    @Test
    void anything_else_is_refused() {
        for (String bad : new String[]{null, "", " ", "-5", "+5", "5,000", "5 000", "5.0", "5.", ".5", "50.255", "1e3", "0x10", "five", "5000XAF",
                "\uFF15\uFF10", "\u0665", "5\u00A0000", "1".repeat(16), "1".repeat(17), "NaN", "Infinity", "50.25.1", "--5"}) {
            assertThat(MoneyFormat.parse(bad, 0)).as("scale 0: " + bad).isEqualTo(OptionalLong.empty());
        }
        assertThat(MoneyFormat.parse("50.25", 0)).isEqualTo(OptionalLong.empty());
        assertThat(MoneyFormat.parse("50.255", 2)).isEqualTo(OptionalLong.empty());
        assertThat(MoneyFormat.parse("50.", 2)).isEqualTo(OptionalLong.empty());
        assertThat(MoneyFormat.parse("5", -1)).isEqualTo(OptionalLong.empty());
        assertThat(MoneyFormat.parse("5", 7)).isEqualTo(OptionalLong.empty());
    }

    @Test
    void reading_back_what_was_written_is_the_identity() {
        for (long minor : new long[]{0, 1, 99, 100, 5025, 123456789, 999_999_999_999L}) {
            for (int scale : new int[]{0, 2, 3}) {
                assertThat(MoneyFormat.parse(MoneyFormat.plain(minor, scale), scale)).as(minor + " at " + scale).isEqualTo(OptionalLong.of(minor));
            }
        }
    }
}
