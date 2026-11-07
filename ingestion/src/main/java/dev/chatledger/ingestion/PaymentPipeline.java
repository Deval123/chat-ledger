package dev.chatledger.ingestion;

import dev.chatledger.extraction.ExtractionResult;
import dev.chatledger.extraction.MessageParser;
import dev.chatledger.extraction.OrderParseContext;
import dev.chatledger.extraction.ParsedPayment;
import dev.chatledger.extraction.PaymentTemplates;
import dev.chatledger.extraction.PaymentTextParser;
import dev.chatledger.extraction.PaymentValidator;
import dev.chatledger.extraction.ValidationResult;

import java.time.Instant;

/**
 * Raw text to a {@link PaymentDecider.Draft}: parse, validate, decide. One pure function. The parser is a parameter so a test
 * can substitute a parser that lies and prove the validator stops it. The validator runs here and nowhere else.
 */
public final class PaymentPipeline {

    private PaymentPipeline() {
    }

    public static PaymentDecider.Draft run(String rawText, PaymentTemplates templates, OrderParseContext context, Instant now) {
        return run(rawText, new PaymentTextParser(templates, context), context, now);
    }

    public static PaymentDecider.Draft run(String rawText, MessageParser<ParsedPayment> parser, OrderParseContext context,
                                           Instant now) {
        ExtractionResult<ParsedPayment> parsed = parser.parse(rawText);
        if (!parsed.isSuccess()) {
            return PaymentDecider.unparsed(parsed.failureReason().orElseThrow());
        }
        ParsedPayment payment = parsed.value().orElseThrow();
        ValidationResult validation = PaymentValidator.validate(payment, context, now);
        if (validation.verdict() != ValidationResult.Verdict.OK) {
            return PaymentDecider.unparsed(validation.reason());
        }
        return PaymentDecider.decide(payment);
    }
}
