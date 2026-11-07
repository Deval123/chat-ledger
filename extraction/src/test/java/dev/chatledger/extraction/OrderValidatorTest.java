package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.chatledger.extraction.ValidationResult.Verdict.NEEDS_REVIEW;
import static dev.chatledger.extraction.ValidationResult.Verdict.OK;
import static dev.chatledger.extraction.ValidationResult.Verdict.REJECT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The last gate. Its central rule: every amount the <em>parser read</em> must be written in the message. A total that
 * the service computes from the catalog is not read by the parser, is not in the text, and never reaches this check
 * (the service tests prove that half; here the validator is shown to hold the first half hard).
 *
 * <p>Requirements: 1.1, 8 (validate: the amount appears in the source). Task 4.1.
 */
class OrderValidatorTest {

    private static final OrderParseContext XAF = new OrderParseContext("XAF", 0);

    private static ParsedOrder order(String raw, Long total, ParsedOrder.OrderLine... lines) {
        return new ParsedOrder("A-1", "Ana", null, List.of(lines), total, null, null, raw);
    }

    private static ParsedOrder.OrderLine line(String label, int qty, Long unit, Long lineTotal) {
        return new ParsedOrder.OrderLine(label, qty, unit, lineTotal);
    }

    @Test
    void amounts_written_in_the_message_pass() {
        ParsedOrder o = order("2 x rice @ 1 500\nTotal: 3000", 3000L, line("rice", 2, 1500L, null));
        assertThat(OrderValidator.validate(o, XAF)).isEqualTo(ValidationResult.ok());
    }

    @Test
    void an_amount_the_parser_reports_but_the_message_does_not_contain_is_refused() {
        assertThat(OrderValidator.validate(order("2 x rice @ 1500", null, line("rice", 2, 1600L, null)), XAF))
                .isEqualTo(ValidationResult.reject(FailureReason.AMOUNT_NOT_IN_SOURCE));
        assertThat(OrderValidator.validate(order("2 x rice\nTotal: 3000", 3100L, line("rice", 2, null, null)), XAF))
                .as("the stated total").isEqualTo(ValidationResult.reject(FailureReason.AMOUNT_NOT_IN_SOURCE));
        assertThat(OrderValidator.validate(order("1 x rice = 500", null, line("rice", 1, null, 600L)), XAF))
                .as("a line total").isEqualTo(ValidationResult.reject(FailureReason.AMOUNT_NOT_IN_SOURCE));
    }

    @Test
    void a_substring_or_part_of_a_longer_number_does_not_count() {
        assertThat(OrderValidator.validate(order("1 x rice @ 15000", null, line("rice", 1, 1500L, null)), XAF).verdict())
                .isEqualTo(REJECT);
        assertThat(OrderValidator.validate(order("1 x rice @ 1500", null, line("rice", 1, 150L, null)), XAF).verdict())
                .isEqualTo(REJECT);
        assertThat(OrderValidator.validate(order("Phone 237 612 345\n1 x rice @ 5", null, line("rice", 1, 612L, null)), XAF).verdict())
                .as("a group of a longer run is not the amount").isEqualTo(REJECT);
    }

    @Test
    void the_check_reads_the_normalised_text_so_other_scripts_and_invisible_characters_do_not_hide_or_fake_an_amount() {
        assertThat(OrderValidator.validate(order("1 x rice @ \u0661\u0665\u0660\u0660", null, line("rice", 1, 1500L, null)), XAF).verdict())
                .isEqualTo(OK);
        assertThat(OrderValidator.validate(order("1 x rice @ 15\u200B00", null, line("rice", 1, 1500L, null)), XAF).verdict())
                .isEqualTo(OK);
        assertThat(OrderValidator.validate(order("1 x rice @ 5\u202E0051", null, line("rice", 1, 1500L, null)), XAF).verdict())
                .isEqualTo(REJECT);
    }

    @Test
    void a_message_with_no_amounts_has_nothing_to_check_so_a_computed_total_is_not_the_validators_business() {
        ParsedOrder o = order("2 x rice\n1 x beans", null, line("rice", 2, null, null), line("beans", 1, null, null));
        assertThat(OrderValidator.validate(o, XAF)).isEqualTo(ValidationResult.ok());
    }

    @Test
    void decimals_written_either_way_match() {
        OrderParseContext eur = new OrderParseContext("EUR", 2);
        assertThat(OrderValidator.validate(order("1 x tea @ 12.50", null, line("tea", 1, 1250L, null)), eur).verdict()).isEqualTo(OK);
        assertThat(OrderValidator.validate(order("1 x tea @ 12", null, line("tea", 1, 1200L, null)), eur).verdict()).isEqualTo(OK);
        assertThat(OrderValidator.validate(order("1 x tea @ 12.50", null, line("tea", 1, 125L, null)), eur).verdict()).isEqualTo(REJECT);
    }

    @Test
    void a_stated_total_that_disagrees_with_the_lines_needs_review_and_is_not_replaced() {
        ParsedOrder o = order("2 x rice @ 1500\nTotal: 3500", 3500L, line("rice", 2, 1500L, null));
        assertThat(OrderValidator.validate(o, XAF)).isEqualTo(ValidationResult.review(FailureReason.TOTAL_MISMATCH));
        ParsedOrder mixed = order("2 x rice @ 1500\n1 x beans = 500\nTotal: 3500", 3500L,
                line("rice", 2, 1500L, null), line("beans", 1, null, 500L));
        assertThat(OrderValidator.validate(mixed, XAF).verdict()).isEqualTo(OK);
    }

    @Test
    void a_total_is_not_compared_when_some_line_has_no_price() {
        ParsedOrder o = order("2 x rice @ 1500\n1 x beans\nTotal: 9999", 9999L, line("rice", 2, 1500L, null), line("beans", 1, null, null));
        assertThat(OrderValidator.validate(o, XAF).verdict()).isEqualTo(OK);
    }

    @Test
    void sums_that_overflow_or_pass_the_cap_are_refused() {
        long big = OrderLimits.MAX_MINOR;
        ParsedOrder o = order("9999 x rice @ " + big + "\nTotal: 1", 1L, line("rice", 9999, big, null));
        assertThat(OrderValidator.validate(o, XAF)).isEqualTo(ValidationResult.reject(FailureReason.AMOUNT_TOO_LARGE));
        List<ParsedOrder.OrderLine> fifty = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            fifty.add(line("item" + i, 1, big, null));
            raw.append("1 x item @ ").append(big).append('\n');
        }
        raw.append("Total: 1");
        assertThat(OrderValidator.validate(order(raw.toString(), 1L, fifty.toArray(new ParsedOrder.OrderLine[0])), XAF).reason())
                .isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
    }

    @Test
    void bounds_are_checked_again_whatever_the_parser_did() {
        assertThat(OrderValidator.validate(order("x", null), XAF).reason()).isEqualTo(FailureReason.NO_ITEMS);
        assertThat(OrderValidator.validate(order("1 x a", null, line("a", 0, null, null)), XAF).reason()).isEqualTo(FailureReason.INVALID_QUANTITY);
        assertThat(OrderValidator.validate(order("1 x a", null, line("a", 10_000, null, null)), XAF).reason()).isEqualTo(FailureReason.INVALID_QUANTITY);
        assertThat(OrderValidator.validate(order("x", null, line("a".repeat(101), 1, null, null)), XAF).reason()).isEqualTo(FailureReason.FIELD_TOO_LONG);
        assertThat(OrderValidator.validate(order("x", null, line("", 1, null, null)), XAF).reason()).isEqualTo(FailureReason.FIELD_TOO_LONG);
        assertThat(OrderValidator.validate(order("1 x a @ 5 = 5", null, line("a", 1, 5L, 5L)), XAF).reason()).isEqualTo(FailureReason.INVALID_AMOUNT);
        assertThat(OrderValidator.validate(order("1 x a = 5", null, line("a", 2, null, 5L)), XAF).reason()).isEqualTo(FailureReason.LINE_TOTAL_NOT_DIVISIBLE);
        assertThat(OrderValidator.validate(order("1 x a @ 0", null, line("a", 1, 0L, null)), XAF).reason()).isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
        assertThat(OrderValidator.validate(order("1 x a @ 1", null, line("a", 1, OrderLimits.MAX_MINOR + 1, null)), XAF).reason()).isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
        List<ParsedOrder.OrderLine> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(line("a", 1, null, null));
        }
        assertThat(OrderValidator.validate(order("x", null, many.toArray(new ParsedOrder.OrderLine[0])), XAF).reason())
                .isEqualTo(FailureReason.TOO_MANY_ITEMS);
        assertThat(OrderValidator.validate(new ParsedOrder("r".repeat(65), null, null, List.of(line("a", 1, null, null)), null, null, null, "x"), XAF).reason())
                .isEqualTo(FailureReason.FIELD_TOO_LONG);
    }

    @Test
    void the_message_currency_must_be_the_tenants() {
        ParsedOrder eur = new ParsedOrder(null, null, null, List.of(line("a", 1, 5L, null)), null, "EUR", null, "1 x a @ 5 EUR");
        assertThat(OrderValidator.validate(eur, XAF)).isEqualTo(ValidationResult.reject(FailureReason.CURRENCY_MISMATCH));
        ParsedOrder none = new ParsedOrder(null, null, null, List.of(line("a", 1, 5L, null)), null, null, null, "1 x a @ 5");
        assertThat(OrderValidator.validate(none, XAF).verdict()).isEqualTo(OK);
    }

    @Test
    void every_review_outcome_is_distinct_from_ok_and_reject() {
        assertThat(NEEDS_REVIEW).isNotEqualTo(OK).isNotEqualTo(REJECT);
    }
}
