package dev.chatledger.orders;

import dev.chatledger.extraction.FailureReason;
import dev.chatledger.ingestion.RejectReason;

import java.util.UUID;

/**
 * What the ingestion service tells its caller. Only identifiers and codes: no text from the message, so printing or
 * logging a result is always safe.
 *
 * @param outcome           What happened.
 * @param orderId           The order created, or the existing order on a duplicate; null if none.
 * @param reason            Why the order is unparsed or needs review (null otherwise).
 * @param rejectReason      Why the input was refused (only for {@code REJECTED_INPUT}).
 * @param retryAfterSeconds Seconds to wait (only for {@code RATE_LIMITED}).
 */
public record IngestionResult(Outcome outcome, UUID orderId, FailureReason reason, RejectReason rejectReason,
                              long retryAfterSeconds) {

    public enum Outcome {
        CREATED,
        CREATED_NEEDS_REVIEW,
        CREATED_UNPARSED,
        /** Same external id, same text: the existing order is returned. */
        DUPLICATE,
        /** Same external id, different text: the first order is kept and nothing is overwritten. */
        DUPLICATE_DIFFERENT_TEXT,
        RATE_LIMITED,
        /** Invalid input; nothing was stored. */
        REJECTED_INPUT
    }

    static IngestionResult created(Outcome outcome, UUID orderId, FailureReason reason) {
        return new IngestionResult(outcome, orderId, reason, null, 0);
    }

    static IngestionResult duplicate(boolean differentText, UUID orderId) {
        return new IngestionResult(differentText ? Outcome.DUPLICATE_DIFFERENT_TEXT : Outcome.DUPLICATE, orderId, null, null, 0);
    }

    static IngestionResult rejected(RejectReason reason) {
        return new IngestionResult(Outcome.REJECTED_INPUT, null, null, reason, 0);
    }

    static IngestionResult rateLimited(long retryAfterSeconds) {
        return new IngestionResult(Outcome.RATE_LIMITED, null, null, null, retryAfterSeconds);
    }

    public boolean stored() {
        return outcome == Outcome.CREATED || outcome == Outcome.CREATED_NEEDS_REVIEW || outcome == Outcome.CREATED_UNPARSED;
    }
}
