package dev.chatledger.ingestion;

import dev.chatledger.extraction.ExtractionResult;
import dev.chatledger.extraction.FailureReason;
import dev.chatledger.extraction.HostilePayments;
import dev.chatledger.extraction.MessageParser;
import dev.chatledger.extraction.OrderParseContext;
import dev.chatledger.extraction.ParsedPayment;
import dev.chatledger.extraction.PaymentLimits;
import dev.chatledger.extraction.PaymentTemplateSpec;
import dev.chatledger.extraction.PaymentTemplates;
import dev.chatledger.extraction.Phone;
import dev.chatledger.extraction.TestSpecs;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static dev.chatledger.ingestion.PaymentDecider.Status.RECEIVED;
import static dev.chatledger.ingestion.PaymentDecider.Status.UNPARSED;
import static dev.chatledger.ingestion.PaymentDecider.Status.UNVERIFIED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * From text to the status a payment starts in: parse, validate, decide. The validator runs here and only here, so a parser
 * that lies is stopped here.
 *
 * <p>Requirements: 2.1, 2.3, 2.5, 8. Tasks 5.1, 5.3.
 */
class PaymentPipelineTest {

    private static final OrderParseContext XAF = new OrderParseContext("XAF", 0);
    private static final Instant NOW = PaymentTemplates.SELF_TEST_NOW;
    private static final PaymentTemplates SET = TestSpecs.goodSet();

    private static PaymentTemplates withForwarded() {
        PaymentTemplateSpec fwd = new PaymentTemplateSpec("fwd", "PROVIDER_T", "XAF", 0, false, "+01:00", "dd/MM/yyyy HH:mm",
                "Forwarded: got {amount} XAF from {senderName} ({senderLast4}) on {paidAt}",
                List.of(
                        TestSpecs.example("Forwarded: got 5000 XAF from Ana K. (1234) on 03/11/2026 10:15", 5000, null, "Ana K.", "1234", null, "2026-11-03T09:15:00Z"),
                        TestSpecs.example("Forwarded: got 700 XAF from Bea (5678) on 02/11/2026 18:40", 700, null, "Bea", "5678", null, "2026-11-02T17:40:00Z"),
                        TestSpecs.example("Forwarded: got 12 500 XAF from Cy O'Neil (4321) on 03/11/2026 11:05", 12500, null, "Cy O'Neil", "4321", null, "2026-11-03T10:05:00Z")),
                List.of("got 5000 XAF from Ana K. (1234) on 03/11/2026 10:15", "Forwarded: got 5000 XAF from Ana K. (12345) on 03/11/2026 10:15",
                        "Forwarded: got 5000 XAF from Ana K. (1234) on", "Forwarded: got 5000 XAF from Ana K. (1234) on 03/11/2026 10:15 ok",
                        "Hi. Forwarded: got 5000 XAF from Ana K. (1234) on 03/11/2026 10:15"));
        return PaymentTemplates.of(List.of(TestSpecs.good(), TestSpecs.goodRef(), fwd));
    }

    private static PaymentDecider.Draft run(String text) {
        return PaymentPipeline.run(text, SET, XAF, NOW);
    }

    @Test
    void a_text_with_a_transaction_id_is_received() {
        PaymentDecider.Draft d = run(HostilePayments.VALID);
        assertThat(d.status()).isEqualTo(RECEIVED);
        assertThat(d.reason()).isNull();
        assertThat(d.parsed().amountMinor()).isEqualTo(5000);
    }

    @Test
    void a_template_that_declares_no_transaction_id_gives_an_unverified_payment() {
        PaymentDecider.Draft d = PaymentPipeline.run("Forwarded: got 5000 XAF from Ana K. (1234) on 03/11/2026 10:15", withForwarded(), XAF, NOW);
        assertThat(d.status()).isEqualTo(UNVERIFIED);
        assertThat(d.parsed().transactionId()).isNull();
        assertThat(d.parsed().senderLast4()).isEqualTo("1234");
        assertThat(d.reason()).isNull();
    }

    @Test
    void free_typed_text_with_an_amount_but_no_template_stays_unparsed_it_is_not_guessed_unverified() {
        for (String text : new String[]{"Ana paid me 5000 XAF yesterday", "received 5000 XAF from Ana K. on 03/11/2026", "5000 XAF",
                "[screenshot] Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15"}) {
            PaymentDecider.Draft d = PaymentPipeline.run(text, withForwarded(), XAF, NOW);
            assertThat(d.status()).as(text).isEqualTo(UNPARSED);
            assertThat(d.reason()).as(text).isEqualTo(FailureReason.NO_TEMPLATE_MATCH);
            assertThat(d.parsed()).isNull();
        }
    }

    @Test
    void an_unparsed_draft_carries_a_code_and_nothing_read_from_the_text() {
        PaymentDecider.Draft d = run(HostilePayments.get("february 31"));
        assertThat(d.status()).isEqualTo(UNPARSED);
        assertThat(d.reason()).isEqualTo(FailureReason.INVALID_DATE);
        assertThat(d.parsed()).isNull();
        assertThat(d.toString()).doesNotContain("Ana").doesNotContain("TX123456");
    }

    @Test
    void the_timestamp_window_is_applied_with_the_clock_the_pipeline_is_given() {
        assertThat(run(HostilePayments.get("far future")).reason()).isEqualTo(FailureReason.DATE_IMPLAUSIBLE);
        assertThat(run(HostilePayments.get("ancient")).reason()).isEqualTo(FailureReason.DATE_IMPLAUSIBLE);
        assertThat(run(HostilePayments.get("far future")).status()).isEqualTo(UNPARSED);
        // The same message is fine when "now" is near it, and too old when "now" moves 40 days on.
        assertThat(PaymentPipeline.run(HostilePayments.VALID, SET, XAF, NOW.plus(Duration.ofDays(30))).status()).isEqualTo(RECEIVED);
        assertThat(PaymentPipeline.run(HostilePayments.VALID, SET, XAF, NOW.plus(Duration.ofDays(40))).reason()).isEqualTo(FailureReason.DATE_IMPLAUSIBLE);
        assertThat(PaymentPipeline.run(HostilePayments.VALID, SET, XAF, NOW.minus(Duration.ofHours(10))).reason())
                .as("ahead of the clock by more than the skew allows").isEqualTo(FailureReason.DATE_IMPLAUSIBLE);
        assertThat(PaymentPipeline.run(HostilePayments.VALID, SET, XAF, Instant.parse("2026-11-03T09:00:00Z")).status())
                .as("paid 09:15Z, now 09:00:00Z: exactly the 15 minutes the skew allows").isEqualTo(RECEIVED);
        assertThat(PaymentPipeline.run(HostilePayments.VALID, SET, XAF, Instant.parse("2026-11-03T08:59:59Z")).reason())
                .as("one second more ahead").isEqualTo(FailureReason.DATE_IMPLAUSIBLE);
        assertThat(PaymentPipeline.run(HostilePayments.VALID, SET, XAF, Instant.parse("2026-11-03T09:05:00Z")).status()).isEqualTo(RECEIVED);
    }

    @Test
    void a_tenant_in_another_currency_never_gets_this_providers_payments() {
        assertThat(PaymentPipeline.run(HostilePayments.VALID, SET, new OrderParseContext("EUR", 2), NOW).reason())
                .isEqualTo(FailureReason.CURRENCY_MISMATCH);
    }

    @Test
    void a_parser_that_lies_about_the_amount_is_stopped_by_the_validator() {
        // The message says 5000. This "parser" reports 9999, which is written nowhere in the message.
        MessageParser<ParsedPayment> liar = text -> new ExtractionResult.Success<>(new ParsedPayment("good", "PROVIDER_T", "TX123456", 9999,
                "XAF", "Ana K.", Phone.of("+999 000 000 1234").orElseThrow(), "1234", null, Instant.parse("2026-11-03T09:15:00Z"), text));
        PaymentDecider.Draft d = PaymentPipeline.run(HostilePayments.VALID, liar, XAF, NOW);
        assertThat(d.status()).isEqualTo(UNPARSED);
        assertThat(d.reason()).isEqualTo(FailureReason.AMOUNT_NOT_IN_SOURCE);
        assertThat(d.parsed()).isNull();
    }

    @Test
    void a_parser_that_fails_gives_its_code() {
        MessageParser<ParsedPayment> failing = text -> new ExtractionResult.Failure<>(FailureReason.AMBIGUOUS_TEMPLATE);
        assertThat(PaymentPipeline.run("anything", failing, XAF, NOW).reason()).isEqualTo(FailureReason.AMBIGUOUS_TEMPLATE);
    }

    @Test
    void every_hostile_confirmation_gets_a_status_and_never_throws() {
        HostilePayments.all().forEach((name, text) -> {
            PaymentDecider.Draft d = run(text);
            assertThat(d.status()).as(name).isNotNull();
            if (d.status() == UNPARSED) {
                assertThat(d.reason()).as(name).isNotNull();
                assertThat(d.parsed()).as(name).isNull();
            } else {
                assertThat(d.parsed()).as(name).isNotNull();
            }
        });
    }

    @Test
    void the_sms_cap_is_a_separate_input_check() {
        assertThat(InputChecks.check("pay-1", "x".repeat(PaymentLimits.MAX_SMS_CHARS), PaymentLimits.MAX_SMS_CHARS)).isEmpty();
        assertThat(InputChecks.check("pay-1", "x".repeat(PaymentLimits.MAX_SMS_CHARS + 1), PaymentLimits.MAX_SMS_CHARS))
                .contains(RejectReason.TEXT_TOO_LARGE);
        assertThat(InputChecks.check("pay-1", "x".repeat(PaymentLimits.MAX_SMS_CHARS + 1))).as("the order cap is larger").isEmpty();
        assertThat(InputChecks.check("pay-1", "a\u0000b", PaymentLimits.MAX_SMS_CHARS)).contains(RejectReason.INVALID_CHARACTERS);
        assertThat(InputChecks.check("bad id", "x", PaymentLimits.MAX_SMS_CHARS)).contains(RejectReason.INVALID_EXTERNAL_ID);
    }
}
