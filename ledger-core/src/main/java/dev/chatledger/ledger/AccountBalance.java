package dev.chatledger.ledger;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The balance of one account.
 *
 * <p>{@code netDebitMinor} is debits minus credits. It is the number that sums
 * to zero across a whole tenant. {@link #signedMinor()} is the same number
 * signed by account type, so a normal balance is positive: an asset or expense
 * shows debits minus credits, a liability, equity or revenue account shows
 * credits minus debits.
 *
 * <p>The sum of {@code signedMinor} over a tenant is not zero. Only the sum of
 * {@code netDebitMinor} is.
 */
public record AccountBalance(UUID accountId, AccountType type, long netDebitMinor) {

    public AccountBalance {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        if (netDebitMinor == Long.MIN_VALUE) {
            // Its negation does not fit in a long. The ledger refuses to reach it.
            throw new LedgerException("Account balance out of range");
        }
    }

    public long signedMinor() {
        return type.normalSide() == Side.DEBIT ? netDebitMinor : -netDebitMinor;
    }

    /** Sum of debits minus credits over all balances. Zero for a consistent tenant. */
    public static BigInteger totalNetDebit(List<AccountBalance> balances) {
        BigInteger sum = BigInteger.ZERO;
        for (AccountBalance b : balances) {
            sum = sum.add(BigInteger.valueOf(b.netDebitMinor()));
        }
        return sum;
    }
}
