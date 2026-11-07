package dev.chatledger.ingestion;

import dev.chatledger.extraction.ExtractionResult;
import dev.chatledger.extraction.MessageParser;
import dev.chatledger.extraction.OrderParseContext;
import dev.chatledger.extraction.OrderTextParser;
import dev.chatledger.extraction.OrderValidator;
import dev.chatledger.extraction.ParsedOrder;
import dev.chatledger.extraction.ValidationResult;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The steps from raw text to a {@link OrderDecider.Draft}, as one pure function: parse, validate, look up the catalog
 * (only if some item has no price in the message), decide. The service wraps this with storage.
 *
 * <p>The parser is a parameter so a test can substitute a parser that lies, and prove the validator stops it.
 */
public final class OrderPipeline {

    /** The draft, and the parse it came from (null when the message is unparsed). */
    public record Result(OrderDecider.Draft draft, ParsedOrder parsed) {
    }

    private OrderPipeline() {
    }

    public static Result run(String rawText, OrderParseContext context,
                             Function<Set<String>, Map<String, Long>> catalog) {
        return run(rawText, context, new OrderTextParser(context), catalog);
    }

    public static Result run(String rawText, OrderParseContext context, MessageParser<ParsedOrder> parser,
                             Function<Set<String>, Map<String, Long>> catalog) {
        ExtractionResult<ParsedOrder> parsed = parser.parse(rawText);
        if (!parsed.isSuccess()) {
            return new Result(OrderDecider.unparsed(context.currency(), parsed.failureReason().orElseThrow()), null);
        }
        ParsedOrder order = parsed.value().orElseThrow();
        ValidationResult validation = OrderValidator.validate(order, context);
        if (validation.verdict() == ValidationResult.Verdict.REJECT) {
            return new Result(OrderDecider.unparsed(context.currency(), validation.reason()), null);
        }
        Set<String> keys = OrderDecider.catalogKeysNeeded(order);
        Map<String, Long> prices = keys.isEmpty() ? Map.of() : catalog.apply(keys);
        return new Result(OrderDecider.decide(order, validation, prices, context.currency()), order);
    }
}
