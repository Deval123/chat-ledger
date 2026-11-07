package dev.chatledger.ledger;

/** The side of a ledger entry. An entry is either a debit or a credit, never both. */
public enum Side {
    DEBIT,
    CREDIT;

    /** The other side; used to build a reversal. */
    public Side opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
