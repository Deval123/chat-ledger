package dev.chatledger.ledger;

import java.util.UUID;

/** A transaction can be reversed at most once. */
public class AlreadyReversedException extends LedgerException {

    public AlreadyReversedException(UUID txnId) {
        super("Transaction " + txnId + " has already been reversed");
    }

    public AlreadyReversedException(UUID txnId, Throwable cause) {
        super("Transaction " + txnId + " has already been reversed", cause);
    }
}
