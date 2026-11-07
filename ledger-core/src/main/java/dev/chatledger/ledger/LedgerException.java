package dev.chatledger.ledger;

/** A ledger rule was violated. Nothing was written. */
public class LedgerException extends RuntimeException {

    public LedgerException(String message) {
        super(message);
    }

    public LedgerException(String message, Throwable cause) {
        super(message, cause);
    }
}
