package dev.chatledger.ledger;

import java.util.UUID;

/**
 * An immutable double-entry posting line.
 *
 * <p>Exactly one of {@code debitMinor} or {@code creditMinor} must be positive;
 * the other must be zero. Amounts are integers in minor currency units.
 *
 * <p>The database enforces that no application role may UPDATE or DELETE rows
 * in this table. Corrections are made via reversal transactions.
 *
 * @param id          Unique entry identifier.
 * @param txnId       The parent transaction that groups balanced entries.
 * @param accountId   Account receiving this posting.
 * @param debitMinor  Debit amount in minor units (0 when this line is a credit).
 * @param creditMinor Credit amount in minor units (0 when this line is a debit).
 */
public record LedgerEntry(
        UUID id,
        UUID txnId,
        UUID accountId,
        long debitMinor,
        long creditMinor
) {
    public LedgerEntry {
        if (debitMinor < 0) throw new IllegalArgumentException("debitMinor must be >= 0");
        if (creditMinor < 0) throw new IllegalArgumentException("creditMinor must be >= 0");
        if (debitMinor > 0 && creditMinor > 0) {
            throw new IllegalArgumentException("An entry cannot have both debit and credit");
        }
        if (debitMinor == 0 && creditMinor == 0) {
            throw new IllegalArgumentException("An entry must have a non-zero debit or credit");
        }
    }
}
