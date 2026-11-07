package dev.chatledger.ledger;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory repository for tests that run without Docker. It refuses what the
 * PostgreSQL schema refuses: entries on another tenant's account, a reversal
 * across tenants, and a second reversal. Balances are summed with BigInteger, so
 * it is not subject to the overflow it is used to detect.
 */
public final class InMemoryLedgerRepository implements LedgerRepository {

    private final Map<UUID, Account> accounts = new LinkedHashMap<>();
    private final Map<UUID, LedgerTransaction> transactions = new LinkedHashMap<>();
    private final Set<UUID> reversed = new HashSet<>();

    @Override
    public void createAccount(Account account) {
        if (accounts.putIfAbsent(account.id(), account) != null) {
            throw new LedgerException("Account already exists: " + account.id());
        }
    }

    @Override
    public Optional<Account> findAccount(UUID tenantId, UUID accountId) {
        return Optional.ofNullable(accounts.get(accountId)).filter(a -> a.tenantId().equals(tenantId));
    }

    @Override
    public void append(LedgerTransaction txn) {
        if (transactions.containsKey(txn.id())) {
            throw new LedgerException("Transaction already exists: " + txn.id());
        }
        for (LedgerEntry e : txn.entries()) {
            if (findAccount(txn.tenantId(), e.accountId()).isEmpty()) {
                throw new LedgerException("Entry account is not in the transaction's tenant");
            }
        }
        if (txn.isReversal()) {
            LedgerTransaction original = transactions.get(txn.reversesTxnId());
            if (original == null || !original.tenantId().equals(txn.tenantId())) {
                throw new LedgerException("Reversed transaction not found in the same tenant");
            }
            if (reversed.contains(txn.reversesTxnId())) {
                throw new AlreadyReversedException(txn.reversesTxnId());
            }
            reversed.add(txn.reversesTxnId());
        }
        transactions.put(txn.id(), txn);
    }

    @Override
    public Optional<LedgerTransaction> findTransaction(UUID tenantId, UUID txnId) {
        return Optional.ofNullable(transactions.get(txnId)).filter(t -> t.tenantId().equals(tenantId));
    }

    @Override
    public boolean isReversed(UUID tenantId, UUID txnId) {
        return findTransaction(tenantId, txnId).isPresent() && reversed.contains(txnId);
    }

    @Override
    public long netDebit(UUID tenantId, UUID accountId) {
        return toLong(sums(tenantId).getOrDefault(accountId, BigInteger.ZERO));
    }

    @Override
    public List<AccountBalance> balances(UUID tenantId) {
        Map<UUID, BigInteger> sums = sums(tenantId);
        List<AccountBalance> result = new ArrayList<>();
        for (Account a : accounts.values()) {
            if (a.tenantId().equals(tenantId)) {
                result.add(new AccountBalance(a.id(), a.type(),
                        toLong(sums.getOrDefault(a.id(), BigInteger.ZERO))));
            }
        }
        return result;
    }

    private Map<UUID, BigInteger> sums(UUID tenantId) {
        Map<UUID, BigInteger> sums = new HashMap<>();
        for (LedgerTransaction t : transactions.values()) {
            if (!t.tenantId().equals(tenantId)) {
                continue;
            }
            for (LedgerEntry e : t.entries()) {
                BigInteger amount = BigInteger.valueOf(e.amountMinor());
                sums.merge(e.accountId(), e.side() == Side.DEBIT ? amount : amount.negate(), BigInteger::add);
            }
        }
        return sums;
    }

    private static long toLong(BigInteger value) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException overflow) {
            throw new LedgerException("Balance does not fit in a long", overflow);
        }
    }
}
