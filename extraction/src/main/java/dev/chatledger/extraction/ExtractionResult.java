package dev.chatledger.extraction;

import java.util.Optional;

/**
 * Outcome of attempting to extract a structured record from raw text.
 *
 * <p>A result is either successful (carries a {@link ParsedMessage}) or failed
 * (carries a reason). Callers check {@link #isSuccess()} before unwrapping.
 *
 * @param <T> The concrete type of the parsed message (order or payment).
 */
public sealed interface ExtractionResult<T extends ParsedMessage>
        permits ExtractionResult.Success, ExtractionResult.Failure {

    boolean isSuccess();

    /** Returns the parsed value, or empty if this is a failure. */
    Optional<T> value();

    /** Returns the failure reason, or empty if this is a success. */
    Optional<String> failureReason();

    // ── Implementations ──────────────────────────────────────────────

    record Success<T extends ParsedMessage>(T parsed) implements ExtractionResult<T> {
        @Override public boolean isSuccess()              { return true; }
        @Override public Optional<T> value()              { return Optional.of(parsed); }
        @Override public Optional<String> failureReason() { return Optional.empty(); }
    }

    record Failure<T extends ParsedMessage>(String reason) implements ExtractionResult<T> {
        @Override public boolean isSuccess()              { return false; }
        @Override public Optional<T> value()              { return Optional.empty(); }
        @Override public Optional<String> failureReason() { return Optional.of(reason); }
    }
}
