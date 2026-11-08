package dev.chatledger.ledger;

import java.util.Optional;
import java.util.UUID;

/** What the pre-posting checks need to read: an account of the tenant, and its current balance. */
public interface AccountLookup {

    Optional<Account> findAccount(UUID tenantId, UUID accountId);

    /** Debits minus credits for one account of the tenant; zero if it has no entries. */
    long netDebit(UUID tenantId, UUID accountId);
}
