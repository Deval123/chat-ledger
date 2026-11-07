package dev.chatledger.ledger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the ledger. Every method takes the tenant id, so there is
 * no way to ask for data without naming whose data it is.
 *
 * <p>The ledger is append-only: there is no update and no delete. An
 * implementation must also refuse what the database refuses (unbalanced
 * transactions, entries on another tenant's account, a second reversal).
 */
public interface LedgerRepository {

    void createAccount(Account account);

    Optional<Account> findAccount(UUID tenantId, UUID accountId);

    /**
     * Writes the transaction and all its entries atomically.
     *
     * @throws AlreadyReversedException if the transaction it reverses was already reversed
     * @throws LedgerException          if a ledger rule is violated; nothing is written
     */
    void append(LedgerTransaction transaction);

    Optional<LedgerTransaction> findTransaction(UUID tenantId, UUID txnId);

    /** True if a reversal of this transaction exists for the tenant. */
    boolean isReversed(UUID tenantId, UUID txnId);

    /** Debits minus credits for one account of the tenant; zero if it has no entries. */
    long netDebit(UUID tenantId, UUID accountId);

    /** Every account of the tenant with its balance, including accounts with no entries. */
    List<AccountBalance> balances(UUID tenantId);
}
