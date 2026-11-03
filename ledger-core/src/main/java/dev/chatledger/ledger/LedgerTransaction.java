package dev.chatledger.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A balanced group of {@link LedgerEntry} records.
 *
 * <p>The balance invariant — sum of debits equals sum of credits — is enforced
 * eagerly so an unbalanced transaction can never be persisted.
 *
 * @param id       Unique transaction identifier.
 * @param tenantId Owning tenant.
 * @param kind     Business event that produced this transaction (e.g. ORDER_ACCEPTED).
 * @param refType  Type of the domain object this transaction references.
 * @param refId    Identifier of the domain object (order, payment, match …).
 * @param entries  The posting lines; must be balanced.
 * @param createdAt When the transaction was recorded.
 */
public record LedgerTransaction(
        UUID id,
        UUID tenantId,
        String kind,
        String refType,
        UUID refId,
        List<LedgerEntry> entries,
        Instant createdAt
) {
    public LedgerTransaction {
        entries = List.copyOf(entries); // defensive copy, keeps immutability
        assertBalanced(entries);
    }

    /** Throws if debits do not equal credits across all entries. */
    private static void assertBalanced(List<LedgerEntry> entries) {
        long totalDebit  = entries.stream().mapToLong(LedgerEntry::debitMinor).sum();
        long totalCredit = entries.stream().mapToLong(LedgerEntry::creditMinor).sum();
        if (totalDebit != totalCredit) {
            throw new IllegalArgumentException(
                    "Unbalanced transaction: debits=%d credits=%d".formatted(totalDebit, totalCredit));
        }
    }
}
