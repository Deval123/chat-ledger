package dev.chatledger.ledger;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A balanced group of entries. It cannot be constructed unless it has at least
 * two entries and total debits equal total credits.
 *
 * <p>Sums use {@link Math#addExact}, so an overflow is a {@link LedgerException}
 * and never a wrapped-around number. A transaction is never edited. A mistake is
 * corrected with {@link #reverse}, which builds a new transaction.
 *
 * @param id            Unique transaction identifier.
 * @param tenantId      Owning tenant.
 * @param kind          Business event, for example ORDER_ACCEPTED.
 * @param refType       Type of the domain object the transaction refers to.
 * @param refId         Identifier of that domain object.
 * @param reversesTxnId The transaction this one reverses, or null.
 * @param createdAt     When the transaction was recorded.
 * @param entries       The lines; at least two, balanced.
 */
public record LedgerTransaction(
        UUID id,
        UUID tenantId,
        String kind,
        String refType,
        UUID refId,
        UUID reversesTxnId,
        Instant createdAt,
        List<LedgerEntry> entries
) {

    public static final String REVERSAL_KIND = "REVERSAL";
    public static final String REVERSAL_REF_TYPE = "LEDGER_TXN";

    public LedgerTransaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(refId, "refId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(entries, "entries");
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        if (refType == null || refType.isBlank()) {
            throw new IllegalArgumentException("refType must not be blank");
        }
        if (id.equals(reversesTxnId)) {
            throw new LedgerException("A transaction cannot reverse itself");
        }
        entries = List.copyOf(entries);
        if (entries.size() < 2) {
            throw new LedgerException(
                    "A transaction needs at least two entries, got " + entries.size());
        }
        long debits = total(entries, Side.DEBIT);
        long credits = total(entries, Side.CREDIT);
        if (debits != credits) {
            throw new LedgerException(
                    "Unbalanced transaction: debits=%d credits=%d".formatted(debits, credits));
        }
    }

    public boolean isReversal() {
        return reversesTxnId != null;
    }

    public long debitTotalMinor() {
        return total(entries, Side.DEBIT);
    }

    public long creditTotalMinor() {
        return total(entries, Side.CREDIT);
    }

    /**
     * Net effect on each account, debits minus credits.
     *
     * <p>The per-side totals already fit in a long (checked at construction), so
     * each account's debit sum and credit sum fit too, and their difference lies
     * in [-Long.MAX_VALUE, Long.MAX_VALUE]. It never overflows.
     */
    public Map<UUID, Long> netDebitByAccount() {
        Map<UUID, Long> debits = new HashMap<>();
        Map<UUID, Long> credits = new HashMap<>();
        for (LedgerEntry e : entries) {
            (e.side() == Side.DEBIT ? debits : credits)
                    .merge(e.accountId(), e.amountMinor(), Math::addExact);
        }
        Map<UUID, Long> net = new HashMap<>();
        for (UUID accountId : concat(debits, credits)) {
            net.put(accountId, debits.getOrDefault(accountId, 0L) - credits.getOrDefault(accountId, 0L));
        }
        return Map.copyOf(net);
    }

    /**
     * Builds the reversal: a new transaction in the same tenant with every entry's
     * side swapped, linked to this one. Nothing is changed on this transaction.
     * A reversal cannot itself be reversed; post a new transaction instead.
     */
    public LedgerTransaction reverse(UUID reversalId, Instant at) {
        if (isReversal()) {
            throw new LedgerException(
                    "Transaction " + id + " is a reversal and cannot be reversed; post a new transaction");
        }
        return new LedgerTransaction(
                reversalId, tenantId, REVERSAL_KIND, REVERSAL_REF_TYPE, id, id, at,
                entries.stream().map(LedgerEntry::mirrored).toList());
    }

    private static long total(List<LedgerEntry> entries, Side side) {
        long sum = 0;
        for (LedgerEntry e : entries) {
            if (e.side() == side) {
                try {
                    sum = Math.addExact(sum, e.amountMinor());
                } catch (ArithmeticException overflow) {
                    throw new LedgerException("Total " + side + " amount overflows a long", overflow);
                }
            }
        }
        return sum;
    }

    private static Iterable<UUID> concat(Map<UUID, Long> a, Map<UUID, Long> b) {
        java.util.Set<UUID> ids = new java.util.HashSet<>(a.keySet());
        ids.addAll(b.keySet());
        return ids;
    }
}
