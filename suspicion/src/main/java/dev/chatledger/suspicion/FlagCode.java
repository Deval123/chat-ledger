package dev.chatledger.suspicion;

/**
 * Machine-readable codes for each suspicion rule.
 *
 * <p>Each code maps to exactly one rule class and one entry in the UI message
 * catalogue. New rules add a new enum constant here.
 *
 * <p>Requirement: 5 (Suspicion flags).
 */
public enum FlagCode {
    /** A transaction id that already exists for this tenant. */
    DUPLICATE_TX,

    /** No provider transaction id present; confirmation is a screenshot or forward. */
    NO_PROVIDER_ID,

    /** Payment amount differs from the order by a small suspicious margin. */
    AMOUNT_NEAR_MISS,

    /** Sender name does not resemble the customer on the order. */
    NAME_MISMATCH,

    /** Payment timestamp is before the order or far outside the expected window. */
    TIME_ANOMALY,

    /** Same sender used a different name on a recent order. */
    SENDER_ALIAS
}
