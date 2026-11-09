package dev.chatledger.matching;

/**
 * The conditions that must ALL hold for an automatic match. They are constants, not configuration. (The eighth condition, that no
 * other candidate is within the tie margin, depends on the whole set of candidates and is checked by {@link MatchPlanner}.)
 */
public enum FloorCode {
    HAS_TRANSACTION_ID,
    AMOUNT_EXACT,
    REFERENCE_UNIQUE_MATCH,
    IDENTITY_PRESENT,
    SAME_CURRENCY,
    TRUSTED_RECORDER,
    AUTO_ENABLED,
    /** Neither the payment nor the order has a flag of its own, open or resolved (task 7, D3). A flag on the pair is checked under the lock. */
    NOT_FLAGGED
}
