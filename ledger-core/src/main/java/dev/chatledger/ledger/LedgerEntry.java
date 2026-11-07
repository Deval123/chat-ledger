package dev.chatledger.ledger;

import java.util.Objects;
import java.util.UUID;

/**
 * One line of a ledger transaction: a debit or a credit of a positive amount.
 *
 * <p>This mirrors the {@code chk_entry_one_side} constraint in V005: exactly one
 * of debit_minor and credit_minor is positive and the other is zero. Here that
 * is a {@link Side} plus one positive amount, so the invalid shapes cannot be
 * built. Amounts are {@code long} minor units; floating point is never used.
 *
 * @param id          Unique entry identifier.
 * @param accountId   The account this line posts to.
 * @param side        DEBIT or CREDIT.
 * @param amountMinor Strictly positive amount in minor units.
 */
public record LedgerEntry(UUID id, UUID accountId, Side side, long amountMinor) {

    public LedgerEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(side, "side");
        if (amountMinor <= 0) {
            throw new LedgerException("Entry amount must be positive, got " + amountMinor);
        }
    }

    public static LedgerEntry debit(UUID accountId, long amountMinor) {
        return new LedgerEntry(UUID.randomUUID(), accountId, Side.DEBIT, amountMinor);
    }

    public static LedgerEntry credit(UUID accountId, long amountMinor) {
        return new LedgerEntry(UUID.randomUUID(), accountId, Side.CREDIT, amountMinor);
    }

    /** The same line on the opposite side, with a new id. */
    LedgerEntry mirrored() {
        return new LedgerEntry(UUID.randomUUID(), accountId, side.opposite(), amountMinor);
    }
}
