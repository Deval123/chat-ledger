package dev.chatledger.ingestion;

/** Why the ingestion service refused input before storing it. A code, never text from the input. */
public enum RejectReason {
    INVALID_EXTERNAL_ID,
    BLANK_TEXT,
    TEXT_TOO_LARGE,
    INVALID_CHARACTERS,
    /** A demo workspace accepts only phone numbers of the synthetic range (task 9, D11; requirement 7.3). */
    REAL_PHONE_IN_DEMO,
    /** A demo workspace holds at most the row cap of orders and payments together (task 9, D12). */
    DEMO_WORKSPACE_FULL
}
