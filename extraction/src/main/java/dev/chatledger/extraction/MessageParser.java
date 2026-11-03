package dev.chatledger.extraction;

/**
 * Strategy interface for extracting a structured record from raw text.
 *
 * <p>Parsers are tried in order; the first one that returns a {@link ExtractionResult.Success}
 * wins. If none match, and an AI extractor is configured, it is tried last.
 *
 * @param <T> The concrete parsed type produced by this parser.
 */
@FunctionalInterface
public interface MessageParser<T extends ParsedMessage> {

    /**
     * Attempts to extract a structured record from {@code rawText}.
     *
     * @param rawText The original message text; never null.
     * @return A success wrapping the parsed value, or a failure with a reason.
     */
    ExtractionResult<T> parse(String rawText);
}
