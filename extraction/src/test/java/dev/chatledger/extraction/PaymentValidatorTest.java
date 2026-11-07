package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static dev.chatledger.extraction.ValidationResult.Verdict.OK;
import static dev.chatledger.extraction.ValidationResult.Verdict.REJECT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The last gate for payments. Its central rule is the one for orders: the amount the parser reports must be written in the
 * message. Then the currency, the formats and the plausibility window.
 *
 * <p>Requirements: 2.1, 8 (validate: the amount appears in the source, currency known, date plausible). Task 5.
 */
class PaymentValidatorTest {

    private static final OrderParseContext XAF = new OrderParseContext("XAF", 0);
    private static final Instant NOW = PaymentTemplates.SELF_TEST_NOW;
    private static final String TEXT = HostilePayments.VALID;
    private static final Instant PAID = Instant.parse("2026-11-03T09:15:00Z");

    private static ParsedPayment payment(long amount, String currency, String tx, String name, Phone phone, String last4, String ref,
                                         Instant paidAt, String raw) {
        return new ParsedPayment("good", "PROVIDER_T", tx, amount, currency, name, phone, last4, ref, paidAt, raw);
    }

    private static ParsedPayment valid() {
        return payment(5000, "XAF", "TX123456", "Ana K.", Phone.of("+999 000 000 1234").orElseThrow(), "1234", null, PAID, TEXT);
    }

    private static ValidationResult check(ParsedPayment p) {
        return PaymentValidator.validate(p, XAF, NOW);
    }

    @Test
    void a_good_payment_passes() {
        assertThat(check(valid())).isEqualTo(ValidationResult.ok());
    }

    @Test
    void an_amount_the_parser_reports_but_the_message_does_not_contain_is_refused() {
        ParsedPayment lying = payment(9999, "XAF", "TX123456", "Ana K.", null, "1234", null, PAID, TEXT);
        assertThat(check(lying)).isEqualTo(ValidationResult.reject(FailureReason.AMOUNT_NOT_IN_SOURCE));
        assertThat(check(payment(500, "XAF", "TX123456", "Ana K.", null, "1234", null, PAID, TEXT)).reason())
                .as("a part of a longer number does not count").isEqualTo(FailureReason.AMOUNT_NOT_IN_SOURCE);
    }

    @Test
    void the_amount_is_looked_for_in_the_normalised_text() {
        String arabic = TEXT.replace("5000", "\u0665\u0660\u0660\u0660");
        assertThat(check(payment(5000, "XAF", "TX123456", "Ana K.", null, "1234", null, PAID, arabic)).verdict()).isEqualTo(OK);
        String bidi = TEXT.replace("5000", "\u202E0005");
        assertThat(check(payment(5000, "XAF", "TX123456", "Ana K.", null, "1234", null, PAID, bidi)).verdict()).isEqualTo(REJECT);
    }

    @Test
    void bounds() {
        for (long bad : new long[]{0, -1, OrderLimits.MAX_MINOR + 1, Long.MAX_VALUE}) {
            assertThat(check(payment(bad, "XAF", "TX123456", null, null, null, null, PAID, TEXT)).reason()).as("" + bad)
                    .isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
        }
        String big = TEXT.replace("5000", Long.toString(OrderLimits.MAX_MINOR));
        assertThat(check(payment(OrderLimits.MAX_MINOR, "XAF", "TX123456", null, null, null, null, PAID, big)).verdict()).isEqualTo(OK);
    }

    @Test
    void the_currency_must_be_the_tenants() {
        assertThat(check(payment(5000, "EUR", "TX123456", null, null, null, null, PAID, TEXT)).reason()).isEqualTo(FailureReason.CURRENCY_MISMATCH);
        assertThat(check(payment(5000, null, "TX123456", null, null, null, null, PAID, TEXT)).reason()).isEqualTo(FailureReason.CURRENCY_MISMATCH);
        assertThat(PaymentValidator.validate(valid(), new OrderParseContext("EUR", 0), NOW).reason()).isEqualTo(FailureReason.CURRENCY_MISMATCH);
    }

    @Test
    void the_timestamp_must_be_inside_the_window_at_both_ends() {
        Instant atFutureLimit = NOW.plus(PaymentLimits.MAX_FUTURE);
        Instant atAgeLimit = NOW.minus(PaymentLimits.MAX_AGE);
        assertThat(check(payment(5000, "XAF", "TX123456", null, null, null, null, atFutureLimit, TEXT)).verdict()).isEqualTo(OK);
        assertThat(check(payment(5000, "XAF", "TX123456", null, null, null, null, atFutureLimit.plusSeconds(1), TEXT)).reason())
                .isEqualTo(FailureReason.DATE_IMPLAUSIBLE);
        assertThat(check(payment(5000, "XAF", "TX123456", null, null, null, null, atAgeLimit, TEXT)).verdict()).isEqualTo(OK);
        assertThat(check(payment(5000, "XAF", "TX123456", null, null, null, null, atAgeLimit.minusSeconds(1), TEXT)).reason())
                .isEqualTo(FailureReason.DATE_IMPLAUSIBLE);
        assertThat(check(payment(5000, "XAF", "TX123456", null, null, null, null, null, TEXT)).reason()).isEqualTo(FailureReason.INVALID_DATE);
        assertThat(PaymentLimits.MAX_FUTURE).isEqualTo(Duration.ofMinutes(15));
        assertThat(PaymentLimits.MAX_AGE).isEqualTo(Duration.ofDays(35));
    }

    @Test
    void the_window_moves_with_the_clock_it_is_given() {
        Instant later = NOW.plus(Duration.ofDays(40));
        assertThat(PaymentValidator.validate(valid(), XAF, later).reason()).isEqualTo(FailureReason.DATE_IMPLAUSIBLE);
        assertThat(PaymentValidator.validate(valid(), XAF, NOW.plus(Duration.ofDays(30))).verdict()).isEqualTo(OK);
    }

    @Test
    void formats_are_checked_again_whatever_the_parser_did() {
        assertThat(check(payment(5000, "XAF", "TX1", null, null, null, null, PAID, TEXT)).reason()).isEqualTo(FailureReason.INVALID_TRANSACTION_ID);
        assertThat(check(payment(5000, "XAF", "TX 123456", null, null, null, null, PAID, TEXT)).reason()).isEqualTo(FailureReason.INVALID_TRANSACTION_ID);
        assertThat(check(payment(5000, "XAF", null, null, null, null, null, PAID, TEXT)).verdict()).as("no id is allowed here (unverified)").isEqualTo(OK);
        assertThat(check(payment(5000, "XAF", "TX123456", null, null, null, "A 1", PAID, TEXT)).reason()).isEqualTo(FailureReason.INVALID_REFERENCE);
        assertThat(check(payment(5000, "XAF", "TX123456", null, null, null, "A-1042", PAID, TEXT)).verdict()).isEqualTo(OK);
        assertThat(check(payment(5000, "XAF", "TX123456", "A", null, null, null, PAID, TEXT)).reason()).isEqualTo(FailureReason.INVALID_NAME);
        assertThat(check(payment(5000, "XAF", "TX123456", "<<<", null, null, null, PAID, TEXT)).reason()).isEqualTo(FailureReason.INVALID_NAME);
        assertThat(check(payment(5000, "XAF", "TX123456", null, null, "12a4", null, PAID, TEXT)).reason()).isEqualTo(FailureReason.INVALID_PHONE);
        assertThat(check(payment(5000, "XAF", "TX123456", null, Phone.of("+999 000 000 1234").orElseThrow(), "9999", null, PAID, TEXT)).reason())
                .as("the last four must be the phone's").isEqualTo(FailureReason.INVALID_PHONE);
    }
}
