package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "The same result" is what lets two templates agree about a message, so every field that matters must be part of the
 * comparison; the template id and the raw text are not.
 *
 * <p>Requirements: 2.1, 8. Task 5.1.
 */
class ParsedPaymentTest {

    private static final Phone PHONE = Phone.of("+999 000 000 1234").orElseThrow();
    private static final Instant T = Instant.parse("2026-11-03T09:15:00Z");

    private static ParsedPayment base() {
        return new ParsedPayment("a", "PROVIDER_T", "TX123456", 5000, "XAF", "Ana K.", PHONE, "1234", "A-1", T, "raw one");
    }

    @Test
    void the_template_that_read_the_text_and_the_text_itself_do_not_count() {
        ParsedPayment other = new ParsedPayment("b", "PROVIDER_T", "TX123456", 5000, "XAF", "Ana K.", PHONE, "1234", "A-1", T, "raw two");
        assertThat(base().sameResultAs(other)).isTrue();
        assertThat(other.sameResultAs(base())).isTrue();
    }

    @Test
    void any_difference_in_what_the_text_says_makes_the_results_differ() {
        Phone otherPhone = Phone.of("+999 000 000 5678").orElseThrow();
        assertThat(base().sameResultAs(new ParsedPayment("a", "PROVIDER_U", "TX123456", 5000, "XAF", "Ana K.", PHONE, "1234", "A-1", T, "r")))
                .as("provider").isFalse();
        assertThat(base().sameResultAs(new ParsedPayment("a", "PROVIDER_T", "TX654321", 5000, "XAF", "Ana K.", PHONE, "1234", "A-1", T, "r")))
                .as("transaction id").isFalse();
        assertThat(base().sameResultAs(new ParsedPayment("a", "PROVIDER_T", null, 5000, "XAF", "Ana K.", PHONE, "1234", "A-1", T, "r")))
                .as("no transaction id").isFalse();
        assertThat(base().sameResultAs(new ParsedPayment("a", "PROVIDER_T", "TX123456", 5001, "XAF", "Ana K.", PHONE, "1234", "A-1", T, "r")))
                .as("amount").isFalse();
        assertThat(base().sameResultAs(new ParsedPayment("a", "PROVIDER_T", "TX123456", 5000, "EUR", "Ana K.", PHONE, "1234", "A-1", T, "r")))
                .as("currency").isFalse();
        assertThat(base().sameResultAs(new ParsedPayment("a", "PROVIDER_T", "TX123456", 5000, "XAF", "Ana L.", PHONE, "1234", "A-1", T, "r")))
                .as("sender name").isFalse();
        assertThat(base().sameResultAs(new ParsedPayment("a", "PROVIDER_T", "TX123456", 5000, "XAF", "Ana K.", otherPhone, "1234", "A-1", T, "r")))
                .as("sender phone").isFalse();
        assertThat(base().sameResultAs(new ParsedPayment("a", "PROVIDER_T", "TX123456", 5000, "XAF", "Ana K.", PHONE, "5678", "A-1", T, "r")))
                .as("last four digits").isFalse();
        assertThat(base().sameResultAs(new ParsedPayment("a", "PROVIDER_T", "TX123456", 5000, "XAF", "Ana K.", PHONE, "1234", "A-2", T, "r")))
                .as("reference").isFalse();
        assertThat(base().sameResultAs(new ParsedPayment("a", "PROVIDER_T", "TX123456", 5000, "XAF", "Ana K.", PHONE, "1234", null, T, "r")))
                .as("no reference").isFalse();
        assertThat(base().sameResultAs(new ParsedPayment("a", "PROVIDER_T", "TX123456", 5000, "XAF", "Ana K.", PHONE, "1234", "A-1", T.plusSeconds(60), "r")))
                .as("time").isFalse();
    }
}
