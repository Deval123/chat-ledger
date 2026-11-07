package dev.chatledger.payments;

import dev.chatledger.extraction.FailureReason;
import dev.chatledger.ingestion.RejectReason;

import java.util.UUID;

/**
 * What the payment service tells its caller: identifiers and codes only, never text from the message, so printing or logging a
 * result is always safe.
 *
 * @param outcome           What happened.
 * @param paymentId         The payment created, or the existing one on a replay; null if none.
 * @param duplicateOf       For {@code CREATED_DUPLICATE_TX}: the payment it repeats.
 * @param reason            Why the payment is unparsed (null otherwise).
 * @param rejectReason      Why the input was refused (only for {@code REJECTED_INPUT}).
 * @param retryAfterSeconds Seconds to wait (only for {@code RATE_LIMITED}).
 */
public record PaymentIngestionResult(Outcome outcome, UUID paymentId, UUID duplicateOf, FailureReason reason,
                                     RejectReason rejectReason, long retryAfterSeconds) {

    public enum Outcome {
        /** Status {@code RECEIVED}: read by a template and carrying a provider transaction id. */
        CREATED,
        /** Status {@code UNVERIFIED}: read by a template that declares no transaction id. */
        CREATED_UNVERIFIED,
        /** Status {@code DUPLICATE}: the transaction id was already recorded; the new record is kept and linked to the first. */
        CREATED_DUPLICATE_TX,
        /** Status {@code UNPARSED}: kept as received, nothing read from it. */
        CREATED_UNPARSED,
        /** Same external id, same text: the existing payment is returned. */
        REPLAY,
        /** Same external id, different text: the first payment is kept, nothing is overwritten. */
        REPLAY_DIFFERENT_TEXT,
        RATE_LIMITED,
        /** Invalid input; nothing was stored. */
        REJECTED_INPUT
    }

    public boolean stored() {
        return outcome == Outcome.CREATED || outcome == Outcome.CREATED_UNVERIFIED || outcome == Outcome.CREATED_DUPLICATE_TX
                || outcome == Outcome.CREATED_UNPARSED;
    }
}
