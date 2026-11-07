package dev.chatledger.ledger;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Posts transactions and reversals. All the checks that do not need a database
 * run here, before anything is written; the database then enforces the same
 * rules again, so no other code path can break them.
 */
public final class LedgerService {

    private final LedgerRepository repository;

    public LedgerService(LedgerRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * Posts a transaction. It is already balanced (its constructor checks that).
     * This checks that every account belongs to the tenant and that no account
     * balance would leave the range [-Long.MAX_VALUE, Long.MAX_VALUE].
     */
    public void post(LedgerTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        if (transaction.isReversal()) {
            throw new LedgerException("Use reverse() to post a reversal");
        }
        append(transaction);
    }

    /**
     * Reverses a transaction of the tenant. The original is never touched.
     *
     * @throws AlreadyReversedException if it was reversed before
     */
    public LedgerTransaction reverse(UUID tenantId, UUID txnId, UUID reversalId, Instant at) {
        LedgerTransaction original = repository.findTransaction(tenantId, txnId)
                .orElseThrow(() -> new LedgerException("Transaction not found for this tenant: " + txnId));
        LedgerTransaction reversal = original.reverse(reversalId, at);
        // Checked first so a second reversal is always reported as such, never as
        // an overflow. The database unique constraint stays the guard against races.
        if (repository.isReversed(tenantId, txnId)) {
            throw new AlreadyReversedException(txnId);
        }
        append(reversal);
        return reversal;
    }

    /** Balance of each account of the tenant. */
    public List<AccountBalance> balances(UUID tenantId) {
        return repository.balances(tenantId);
    }

    /** Debits minus credits over the whole tenant. Always zero. */
    public BigInteger trialBalance(UUID tenantId) {
        return AccountBalance.totalNetDebit(repository.balances(tenantId));
    }

    private void append(LedgerTransaction transaction) {
        UUID tenantId = transaction.tenantId();
        for (Map.Entry<UUID, Long> change : transaction.netDebitByAccount().entrySet()) {
            repository.findAccount(tenantId, change.getKey())
                    .orElseThrow(() -> new LedgerException(
                            "Account " + change.getKey() + " does not belong to tenant " + tenantId));
            long current = repository.netDebit(tenantId, change.getKey());
            long next;
            try {
                next = Math.addExact(current, change.getValue());
            } catch (ArithmeticException overflow) {
                throw new LedgerException(
                        "Posting would overflow the balance of account " + change.getKey(), overflow);
            }
            if (next == Long.MIN_VALUE) {
                throw new LedgerException(
                        "Posting would overflow the balance of account " + change.getKey());
            }
        }
        repository.append(transaction);
    }
}
