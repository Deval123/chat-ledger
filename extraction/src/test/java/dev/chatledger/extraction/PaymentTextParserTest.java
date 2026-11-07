package dev.chatledger.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payment parser with a proven template set: what it reads, what it refuses, and the rule that templates which disagree
 * about a message are never resolved by order.
 *
 * <p>Requirements: 2.1, 2.3, 8.1. Task 5.1.
 */
class PaymentTextParserTest {

    private static final OrderParseContext XAF = new OrderParseContext("XAF", 0);
    private final PaymentTextParser parser = new PaymentTextParser(TestSpecs.goodSet(), XAF);

    private ParsedPayment ok(String text) {
        ExtractionResult<ParsedPayment> r = parser.parse(text);
        assertThat(r.failureReason()).as("should parse").isEmpty();
        return r.value().orElseThrow();
    }

    private FailureReason refused(String text) {
        ExtractionResult<ParsedPayment> r = parser.parse(text);
        assertThat(r.isSuccess()).as("should be refused").isFalse();
        return r.failureReason().orElseThrow();
    }

    @Test
    void a_confirmation_gives_every_field_and_keeps_the_text() {
        ParsedPayment p = ok(HostilePayments.VALID);
        assertThat(p.templateId()).isEqualTo("good");
        assertThat(p.provider()).isEqualTo("PROVIDER_T");
        assertThat(p.transactionId()).isEqualTo("TX123456");
        assertThat(p.amountMinor()).isEqualTo(5000);
        assertThat(p.currency()).isEqualTo("XAF");
        assertThat(p.senderName()).isEqualTo("Ana K.");
        assertThat(p.senderLast4()).isEqualTo("1234");
        assertThat(p.senderPhone()).isEqualTo(Phone.of("+999 000 000 1234").orElseThrow());
        assertThat(p.reference()).isNull();
        assertThat(p.paidAt()).isEqualTo(java.time.Instant.parse("2026-11-03T09:15:00Z"));
        assertThat(p.rawText()).isEqualTo(HostilePayments.VALID);
    }

    @Test
    void the_provider_comes_from_the_template_never_from_the_message() {
        assertThat(ok(HostilePayments.VALID.replace("Received", "Received")).provider()).isEqualTo("PROVIDER_T");
        assertThat(refused("Received 5000 XAF from PROVIDER_Z (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15 PROVIDER_Z")).isNotNull();
    }

    @Test
    void with_and_without_a_reference_two_templates_give_a_reference_or_null() {
        ParsedPayment with = ok("Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. Ref A-1042. 03/11/2026 10:15");
        ParsedPayment without = ok(HostilePayments.VALID);
        assertThat(with.templateId()).isEqualTo("good-ref");
        assertThat(with.reference()).isEqualTo("A-1042");
        assertThat(without.templateId()).isEqualTo("good");
        assertThat(without.reference()).isNull();
    }

    @Test
    void the_full_number_is_kept_only_as_a_phone_and_prints_masked() {
        ParsedPayment p = ok(HostilePayments.VALID);
        assertThat(p.toString()).doesNotContain("Ana").doesNotContain("1234").doesNotContain("TX123456").doesNotContain("999")
                .doesNotContain("Received");
        assertThat(p.senderPhone().toString()).isEqualTo("Phone[****1234]");
    }

    @Test
    void messages_that_fit_no_template_are_not_guessed_at() {
        assertThat(refused("Hello, I paid you 5000 XAF yesterday")).isEqualTo(FailureReason.NO_TEMPLATE_MATCH);
        assertThat(refused(HostilePayments.get("an order message"))).isEqualTo(FailureReason.NO_TEMPLATE_MATCH);
        assertThat(refused(HostilePayments.get("an order message in one line"))).isEqualTo(FailureReason.NO_TEMPLATE_MATCH);
        assertThat(refused(HostilePayments.get("prefix wrapper"))).isEqualTo(FailureReason.NO_TEMPLATE_MATCH);
        assertThat(refused(HostilePayments.get("suffix wrapper"))).isEqualTo(FailureReason.NO_TEMPLATE_MATCH);
        assertThat(refused(HostilePayments.get("message twice"))).isEqualTo(FailureReason.NO_TEMPLATE_MATCH);
        assertThat(refused(HostilePayments.get("upper-case wording"))).isEqualTo(FailureReason.NO_TEMPLATE_MATCH);
    }

    @Test
    void empty_and_oversized_input() {
        assertThat(refused(null)).isEqualTo(FailureReason.EMPTY_INPUT);
        assertThat(refused("")).isEqualTo(FailureReason.EMPTY_INPUT);
        assertThat(refused(" \n\t ")).isEqualTo(FailureReason.EMPTY_INPUT);
        assertThat(refused("\u200B\u200B")).isEqualTo(FailureReason.EMPTY_INPUT);
        assertThat(refused("A".repeat(PaymentLimits.MAX_SMS_CHARS + 1))).isEqualTo(FailureReason.INPUT_TOO_LARGE);
        assertThat(refused("A".repeat(PaymentLimits.MAX_SMS_CHARS))).isEqualTo(FailureReason.NO_TEMPLATE_MATCH);
    }

    @Test
    void text_that_grows_when_normalised_is_capped_after_normalising() {
        // U+FDFA is one character that NFKC turns into eighteen: 200 of them are within the input cap and far over the normalised cap.
        String grows = "\uFDFA".repeat(200);
        assertThat(grows.length()).isLessThanOrEqualTo(PaymentLimits.MAX_SMS_CHARS);
        assertThat(refused(grows)).isEqualTo(FailureReason.INPUT_TOO_LARGE);
        assertThat(refused("\uFDFA".repeat(100))).as("1,800 characters normalised is under the cap").isEqualTo(FailureReason.NO_TEMPLATE_MATCH);
    }

    @Test
    void text_is_normalised_before_matching_so_spacing_and_invisible_characters_do_not_change_the_result() {
        ParsedPayment reference = ok(HostilePayments.VALID);
        for (String name : new String[]{"double spaces and tabs", "zero-width inside a keyword", "arabic-indic digits", "full-width digits",
                "narrow no-break space in the amount"}) {
            ParsedPayment p = ok(HostilePayments.get(name));
            assertThat(p.amountMinor()).as(name).isEqualTo(5000);
            assertThat(p.transactionId()).as(name).isEqualTo("TX123456");
            assertThat(p.rawText()).as(name + ": raw text untouched").isEqualTo(HostilePayments.get(name));
        }
        assertThat(reference.amountMinor()).isEqualTo(5000);
        // The override character is removed, so the amount is read as written, "0005": a leading zero, refused (not 5, not 5000).
        assertThat(refused(HostilePayments.get("bidi override in the amount"))).isEqualTo(FailureReason.INVALID_AMOUNT);
        // An invisible character inside an id is removed, so the id is the one a person sees.
        assertThat(ok(HostilePayments.get("zero-width in the transaction id")).transactionId()).isEqualTo("TX123456");
    }

    @Test
    void values_that_pass_the_hole_but_fail_their_typed_parser_give_that_parsers_code() {
        assertThat(refused(HostilePayments.get("february 31"))).isEqualTo(FailureReason.INVALID_DATE);
        assertThat(refused(HostilePayments.get("month 13"))).isEqualTo(FailureReason.INVALID_DATE);
        assertThat(refused(HostilePayments.get("hour 24"))).isEqualTo(FailureReason.INVALID_DATE);
        assertThat(refused(HostilePayments.get("minute 60"))).isEqualTo(FailureReason.INVALID_DATE);
        assertThat(refused(HostilePayments.get("short date"))).isEqualTo(FailureReason.INVALID_DATE);
        assertThat(refused(HostilePayments.get("leap day that does not exist"))).isEqualTo(FailureReason.INVALID_DATE);
        assertThat(ok(HostilePayments.get("leap day that exists")).paidAt()).isEqualTo(java.time.Instant.parse("2028-02-29T09:15:00Z"));
        assertThat(refused(HostilePayments.get("amount of twenty nines"))).isEqualTo(FailureReason.AMOUNT_TOO_LARGE);
        assertThat(refused(HostilePayments.get("amount zero"))).isEqualTo(FailureReason.INVALID_AMOUNT);
        assertThat(refused(HostilePayments.get("amount with bad grouping"))).isEqualTo(FailureReason.INVALID_AMOUNT);
        assertThat(refused(HostilePayments.get("amount with a decimal"))).isEqualTo(FailureReason.INVALID_AMOUNT);
    }

    @Test
    void characters_a_hole_does_not_allow_stop_the_match_so_markup_and_injection_in_holes_never_become_data() {
        for (String name : new String[]{"markup as the sender name", "script as the sender name", "sql as the sender name",
                "formula as the sender name", "template expression as the transaction id", "sql as the transaction id",
                "path as the transaction id", "format string as the sender name", "log forging with CRLF", "log forging in the name"}) {
            assertThat(refused(HostilePayments.get(name))).as(name).isEqualTo(FailureReason.NO_TEMPLATE_MATCH);
        }
        ParsedPayment allowed = ok(HostilePayments.get("quote and dash characters the name allows"));
        assertThat(allowed.senderName()).as("allowed characters are kept as data, never interpreted").isEqualTo("x'--");
    }

    // ── Several templates ──────────────────────────────────────────────────

    private static PaymentTemplates overlapping(String providerA, String providerB) {
        return PaymentTemplates.unchecked(List.of(
                PaymentTemplate.compile(TestSpecs.with("one", providerA, "XAF", 0, true, "+01:00", "dd/MM/yyyy HH:mm", TestSpecs.GOOD)),
                PaymentTemplate.compile(TestSpecs.with("two", providerB, "XAF", 0, true, "+01:00", "dd/MM/yyyy HH:mm", TestSpecs.GOOD))));
    }

    @Test
    void two_templates_that_match_with_different_results_make_the_message_ambiguous_and_unparsed() {
        var ambiguous = new PaymentTextParser(overlapping("PROVIDER_ONE", "PROVIDER_TWO"), XAF).parse(HostilePayments.VALID);
        assertThat(ambiguous.failureReason()).contains(FailureReason.AMBIGUOUS_TEMPLATE);
        assertThat(ambiguous.value()).isEmpty();
    }

    @Test
    void two_templates_that_match_with_the_same_result_are_not_ambiguous() {
        var same = new PaymentTextParser(overlapping("PROVIDER_ONE", "PROVIDER_ONE"), XAF).parse(HostilePayments.VALID);
        assertThat(same.isSuccess()).isTrue();
        assertThat(same.value().orElseThrow().templateId()).as("the first by order, only because the results are identical").isEqualTo("one");
    }

    @Test
    void if_one_matching_template_cannot_read_the_values_and_another_can_it_is_still_ambiguous() {
        PaymentTemplate strictDate = PaymentTemplate.compile(TestSpecs.with("two", "PROVIDER_T", "XAF", 0, true, "+01:00",
                "yyyy-MM-dd HH:mm", TestSpecs.GOOD));
        PaymentTemplates set = PaymentTemplates.unchecked(List.of(TestSpecs.compile(TestSpecs.GOOD), strictDate));
        assertThat(new PaymentTextParser(set, XAF).parse(HostilePayments.VALID).failureReason()).contains(FailureReason.AMBIGUOUS_TEMPLATE);
    }

    @Test
    void a_currency_hole_must_agree_with_the_templates_currency() {
        String t = "Got {amount} {currency} from {senderName} ({senderPhone}). Txn {transactionId}. {paidAt}";
        PaymentTemplates set = PaymentTemplates.unchecked(List.of(TestSpecs.compile(t)));
        PaymentTextParser p = new PaymentTextParser(set, XAF);
        assertThat(p.parse("Got 5000 FCFA from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15").isSuccess()).isTrue();
        assertThat(p.parse("Got 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15").isSuccess()).isTrue();
        assertThat(p.parse("Got 5000 EUR from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15").failureReason())
                .contains(FailureReason.CURRENCY_MISMATCH);
        assertThat(p.parse("Got 5000 ABC from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15").failureReason())
                .contains(FailureReason.CURRENCY_MISMATCH);
    }

    @Test
    void a_template_with_a_last_four_hole_reads_no_phone() {
        String t = "Forwarded: got {amount} XAF from {senderName} ({senderLast4}) on {paidAt}";
        PaymentTemplate template = PaymentTemplate.compile(TestSpecs.with("fwd", "PROVIDER_T", "XAF", 0, false, "+01:00", "dd/MM/yyyy HH:mm", t));
        ParsedPayment p = new PaymentTextParser(PaymentTemplates.unchecked(List.of(template)), XAF)
                .parse("Forwarded: got 5000 XAF from Ana K. (1234) on 03/11/2026 10:15").value().orElseThrow();
        assertThat(p.senderPhone()).isNull();
        assertThat(p.senderLast4()).isEqualTo("1234");
        assertThat(p.transactionId()).isNull();
    }
}
