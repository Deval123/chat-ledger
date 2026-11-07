package dev.chatledger.ingestion;

/** Why the ingestion service refused input before reading it. A code, never text from the input. */
public enum RejectReason {
    INVALID_EXTERNAL_ID,
    BLANK_TEXT,
    TEXT_TOO_LARGE,
    INVALID_CHARACTERS
}
