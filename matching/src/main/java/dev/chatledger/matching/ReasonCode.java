package dev.chatledger.matching;

/** Why a pair scored as it did. A code, never a sentence: the page writes the sentence, in the reader's language. */
public enum ReasonCode {
    AMOUNT_EXACT,
    AMOUNT_PARTIAL,
    AMOUNT_OVER,
    REFERENCE_MATCH,
    /** The code matched, but another open order has the same code, so it proves nothing (0 points). */
    REFERENCE_AMBIGUOUS,
    IDENTITY_PHONE,
    IDENTITY_NAME_FULL,
    IDENTITY_NAME_INITIAL,
    IDENTITY_LAST4,
    TIME_IN_WINDOW,
    /** The payment has no provider transaction id, so its score is held below the automatic threshold (0 points). */
    UNVERIFIED_CAPPED
}
