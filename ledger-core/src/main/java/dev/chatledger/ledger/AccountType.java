package dev.chatledger.ledger;

/**
 * Standard double-entry account types.
 *
 * <p>Normal balance: ASSET and EXPENSE increase with a debit;
 * LIABILITY, EQUITY, and REVENUE increase with a credit.
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE
}
