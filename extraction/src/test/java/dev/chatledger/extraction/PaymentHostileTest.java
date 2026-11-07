package dev.chatledger.extraction;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Every hostile confirmation and thousands of random ones through the parser and the validator. Whatever the input: no
 * exception, every cap holds, every value that comes out is clean, and any amount that passes validation is written in the
 * message. The time bound is generous; the deterministic proof of bounded work is {@link PaymentTemplateScanTest}.
 *
 * <p>Requirements: 2, 8, 9. Task 5.
 */
class PaymentHostileTest {

    private static final OrderParseContext XAF = new OrderParseContext("XAF", 0);
    private static final PaymentTemplates SET = TestSpecs.goodSet();

    private static void assertInvariants(String text) {
        ExtractionResult<ParsedPayment> r = new PaymentTextParser(SET, XAF).parse(text);
        if (!r.isSuccess()) {
            assertThat(r.failureReason()).isPresent();
            return;
        }
        ParsedPayment p = r.value().orElseThrow();
        assertThat(p.rawText()).isEqualTo(text);
        assertThat(p.provider()).isEqualTo("PROVIDER_T");
        assertThat(p.amountMinor()).isPositive();
        if (p.transactionId() != null) {
            assertThat(p.transactionId()).hasSizeBetween(PaymentLimits.MIN_TRANSACTION_ID, PaymentLimits.MAX_TRANSACTION_ID)
                    .matches("[A-Za-z0-9._:-]+");
        }
        if (p.senderName() != null) {
            assertThat(p.senderName()).hasSizeBetween(PaymentLimits.MIN_NAME, PaymentLimits.MAX_NAME);
            p.senderName().codePoints().forEach(cp -> {
                assertThat(Character.isISOControl(cp)).isFalse();
                assertThat(Character.getType(cp)).isNotEqualTo(Character.FORMAT);
                assertThat("<>&\"=;$%{}\\/@#()[]|`".indexOf(cp)).as("a character a name never contains").isNegative();
            });
        }
        if (p.reference() != null) {
            assertThat(p.reference()).hasSizeLessThanOrEqualTo(OrderLimits.MAX_REF).matches("[A-Za-z0-9._:-]+");
        }
        assertThat(p.toString()).doesNotContain(p.rawText());
        ValidationResult v = PaymentValidator.validate(p, XAF, PaymentTemplates.SELF_TEST_NOW);
        assertThat(v.verdict()).isNotEqualTo(ValidationResult.Verdict.NEEDS_REVIEW);
        if (v.verdict() == ValidationResult.Verdict.OK) {
            String digits = TextNormalizer.normalize(text).replaceAll("[^0-9]", "");
            assertThat(digits).as("the amount is written in the message").contains(Long.toString(p.amountMinor()));
        }
    }

    @Test
    void every_hostile_confirmation_is_read_safely_or_refused() {
        HostilePayments.all().forEach((name, text) ->
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertInvariants(text), name));
    }

    @Test
    void every_hostile_order_message_is_just_not_a_payment() {
        HostileCorpus.all().forEach((name, text) -> {
            ExtractionResult<ParsedPayment> r = new PaymentTextParser(SET, XAF).parse(text);
            assertThat(r.isSuccess()).as(name).isFalse();
        });
    }

    @Test
    void the_outcomes_that_matter() {
        PaymentTextParser p = new PaymentTextParser(SET, XAF);
        assertThat(p.parse(HostilePayments.VALID).isSuccess()).isTrue();
        for (String name : new String[]{"prefix wrapper", "suffix wrapper", "message twice", "message inside quotes", "line break inside",
                "markup as the sender name", "script as the sender name", "sql as the sender name", "formula as the sender name",
                "log forging with CRLF", "phone too short", "phone with letters", "transaction id too short", "huge input", "empty",
                "only whitespace", "only the keyword", "lower-case wording", "upper-case wording"}) {
            assertThat(p.parse(HostilePayments.get(name)).isSuccess()).as(name).isFalse();
        }
        for (String name : new String[]{"february 31", "month 13", "zero date", "hour 24", "minute 60", "short date", "amount zero",
                "amount of twenty nines", "amount with bad grouping", "amount with a decimal", "date with letters"}) {
            assertThat(p.parse(HostilePayments.get(name)).isSuccess()).as(name).isFalse();
        }
        // These read fine; the validator, with the clock, decides about the date.
        for (String name : new String[]{"far future", "ancient"}) {
            ParsedPayment parsed = p.parse(HostilePayments.get(name)).value().orElseThrow();
            assertThat(PaymentValidator.validate(parsed, XAF, PaymentTemplates.SELF_TEST_NOW).reason()).as(name).isEqualTo(FailureReason.DATE_IMPLAUSIBLE);
        }
    }

    @Property(tries = 4000)
    void any_text_is_read_or_refused_without_an_exception(@ForAll("messages") String text) {
        assertInvariants(text);
    }

    @Provide
    Arbitrary<String> messages() {
        Arbitrary<String> pieces = Arbitraries.of(
                "Received ", "Received 5000 XAF from ", "Ana K.", "Bea", " (", ")", "+999 000 000 1234", ". Txn ", "TX123456", "TX1",
                ". Ref ", "A-1042", ". ", "03/11/2026 10:15", "31/02/2026 10:15", "XAF", "FCFA", " ", "  ", "\t", "\n", "\r\n", "5000",
                "12 500", "0", "9999999999999999999999", "\u202E", "\u200B", "\u0665\u0660", "\uFF15", "<", ">", "'", "\"", ";", "--",
                "=", "%s", "${1}", "\u0000", "\uD800", "\uD83D\uDE00", "a", "Z", "\u00E9", "x".repeat(60), "9".repeat(30));
        return pieces.list().ofMinSize(1).ofMaxSize(30).map(l -> String.join("", l));
    }

    @Property(tries = 3000)
    void ten_times_more_input_does_not_cost_more_work(@ForAll("junk") String junk) {
        PaymentTemplate t = SET.all().get(0);
        long base = t.scan("Received " + junk).steps();
        long more = t.scan("Received " + junk.repeat(10)).steps();
        assertThat(more).isLessThanOrEqualTo(Math.max(base, 1) * 12 + 50);
    }

    @Provide
    Arbitrary<String> junk() {
        return Arbitraries.of("9", " ", "A", "A ", "9 ", "-", ".", "(", "\u00E9", "x_", "9,").list().ofMinSize(1).ofMaxSize(40)
                .map(l -> String.join("", l));
    }
}
