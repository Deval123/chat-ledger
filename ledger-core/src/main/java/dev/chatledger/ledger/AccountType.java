package dev.chatledger.ledger;

/**
 * Standard double-entry account types.
 *
 * <p>The normal side is the side that increases the balance: debit for assets
 * and expenses, credit for liabilities, equity and revenue.
 */
public enum AccountType {
    ASSET(Side.DEBIT),
    LIABILITY(Side.CREDIT),
    EQUITY(Side.CREDIT),
    REVENUE(Side.CREDIT),
    EXPENSE(Side.DEBIT);

    private final Side normalSide;

    AccountType(Side normalSide) {
        this.normalSide = normalSide;
    }

    public Side normalSide() {
        return normalSide;
    }
}
