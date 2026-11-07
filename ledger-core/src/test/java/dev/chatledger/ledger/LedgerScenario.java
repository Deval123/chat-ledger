package dev.chatledger.ledger;

import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs a random sequence of postings and reversals against a {@link LedgerService}
 * and checks every ledger invariant after each step. It is used by the property
 * tests on the in-memory repository and, through the ledger-core test-jar, on
 * PostgreSQL.
 *
 * <p>The oracle is a map of BigInteger balances kept here, independent of the
 * code under test. For every operation it predicts whether the ledger must
 * accept it (the result must stay in [-Long.MAX_VALUE, Long.MAX_VALUE]). The
 * ledger must accept exactly the operations the oracle accepts, so a bug that
 * rejects everything fails as surely as one that wraps around.
 */
public final class LedgerScenario {

    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger MIN = MAX.negate();

    // ── Operations ──────────────────────────────────────────────────────────

    public sealed interface Op permits Transfer, Split, Unbalanced, Reverse, PostAndReverse {}

    /** Debit {@code from}, credit {@code to}, same amount. */
    public record Transfer(int from, int to, long amount) implements Op {}

    /** Debit one account with a + b, credit two accounts with a and b. */
    public record Split(int debit, int creditA, int creditB, long a, long b) implements Op {}

    /** A transaction whose sides differ. The domain must refuse it. */
    public record Unbalanced(int debit, int credit, long debitAmount, long creditAmount) implements Op {}

    /** Reverse the pick-th posted transaction (modulo the number posted). */
    public record Reverse(int pick) implements Op {}

    /** Post a transfer and immediately reverse it; every balance must be unchanged. */
    public record PostAndReverse(int from, int to, long amount) implements Op {}

    // ── State ───────────────────────────────────────────────────────────────

    private record Posted(LedgerTransaction txn, boolean[] reversed) {}

    private final LedgerService service;
    private final LedgerRepository repository;
    private final UUID tenantId;
    private final List<Account> accounts;
    private final Map<UUID, BigInteger> oracle = new HashMap<>();
    private final List<Posted> posted = new ArrayList<>();
    private final List<LedgerTransaction> everyCommitted = new ArrayList<>();
    private int accepted;
    private int rejected;

    public LedgerScenario(LedgerService service, LedgerRepository repository, UUID tenantId,
                          List<Account> accounts) {
        this.service = service;
        this.repository = repository;
        this.tenantId = tenantId;
        this.accounts = List.copyOf(accounts);
        accounts.forEach(a -> oracle.put(a.id(), BigInteger.ZERO));
    }

    /** One account of each of four types, created in the repository. */
    public static List<Account> createAccounts(LedgerRepository repository, UUID tenantId) {
        List<Account> accounts = List.of(
                new Account(UUID.randomUUID(), tenantId, "AR", "Accounts receivable", AccountType.ASSET),
                new Account(UUID.randomUUID(), tenantId, "CLR", "Clearing", AccountType.LIABILITY),
                new Account(UUID.randomUUID(), tenantId, "REV", "Revenue", AccountType.REVENUE),
                new Account(UUID.randomUUID(), tenantId, "EXP", "Expenses", AccountType.EXPENSE));
        accounts.forEach(repository::createAccount);
        return accounts;
    }

    public int accepted() {
        return accepted;
    }

    public int rejected() {
        return rejected;
    }

    // ── Running ─────────────────────────────────────────────────────────────

    public void run(List<Op> ops) {
        checkInvariants();
        for (Op op : ops) {
            apply(op);
            checkInvariants();
        }
        checkEveryCommittedTransactionIsBalanced();
    }

    private void apply(Op op) {
        switch (op) {
            case Transfer t -> post(transfer(t.from(), t.to(), t.amount()));
            case Split s -> post(split(s));
            case Unbalanced u -> refuseUnbalanced(u);
            case Reverse r -> reverse(r.pick());
            case PostAndReverse p -> postAndReverse(p);
        }
    }

    private LedgerTransaction transfer(int from, int to, long amount) {
        return newTxn(List.of(LedgerEntry.debit(id(from), amount), LedgerEntry.credit(id(to), amount)));
    }

    private LedgerTransaction split(Split s) {
        return newTxn(List.of(
                LedgerEntry.debit(id(s.debit()), s.a() + s.b()),
                LedgerEntry.credit(id(s.creditA()), s.a()),
                LedgerEntry.credit(id(s.creditB()), s.b())));
    }

    private LedgerTransaction newTxn(List<LedgerEntry> entries) {
        return new LedgerTransaction(UUID.randomUUID(), tenantId, "TEST", "TEST", UUID.randomUUID(),
                null, now(), entries);
    }

    private void post(LedgerTransaction txn) {
        Map<UUID, BigInteger> after = project(delta(txn));
        boolean mustAccept = after != null;
        try {
            service.post(txn);
        } catch (LedgerException e) {
            assertThat(mustAccept)
                    .as("a valid posting was rejected: %s", e.getMessage()).isFalse();
            rejected++;
            return;
        }
        assertThat(mustAccept).as("a posting that overflows a balance was accepted").isTrue();
        oracle.putAll(after);
        posted.add(new Posted(txn, new boolean[1]));
        everyCommitted.add(txn);
        accepted++;
    }

    private void refuseUnbalanced(Unbalanced u) {
        assertThatThrownBy(() -> newTxn(List.of(
                LedgerEntry.debit(id(u.debit()), u.debitAmount()),
                LedgerEntry.credit(id(u.credit()), u.creditAmount()))))
                .isInstanceOf(LedgerException.class);
        rejected++;
    }

    private void reverse(int pick) {
        if (posted.isEmpty()) {
            return;
        }
        Posted target = posted.get(Math.floorMod(pick, posted.size()));
        if (target.reversed()[0]) {
            assertThatThrownBy(() -> service.reverse(tenantId, target.txn().id(), UUID.randomUUID(), now()))
                    .isInstanceOf(AlreadyReversedException.class);
            rejected++;
            return;
        }
        Map<UUID, BigInteger> after = project(negate(delta(target.txn())));
        boolean mustAccept = after != null;
        LedgerTransaction reversal;
        try {
            reversal = service.reverse(tenantId, target.txn().id(), UUID.randomUUID(), now());
        } catch (LedgerException e) {
            assertThat(e).isNotInstanceOf(AlreadyReversedException.class);
            assertThat(mustAccept).as("a valid reversal was rejected: %s", e.getMessage()).isFalse();
            rejected++;
            return;
        }
        assertThat(mustAccept).as("a reversal that overflows a balance was accepted").isTrue();
        assertThat(reversal.reversesTxnId()).isEqualTo(target.txn().id());
        assertThat(reversal.entries()).hasSameSizeAs(target.txn().entries());
        oracle.putAll(after);
        target.reversed()[0] = true;
        everyCommitted.add(reversal);
        accepted++;
    }

    private void postAndReverse(PostAndReverse p) {
        Map<UUID, Long> before = actualBalances();
        LedgerTransaction txn = transfer(p.from(), p.to(), p.amount());
        int acceptedBefore = accepted;
        post(txn);
        if (accepted == acceptedBefore) {
            return; // rejected for overflow; nothing was written
        }
        Posted justPosted = posted.get(posted.size() - 1);
        LedgerTransaction reversal = service.reverse(tenantId, txn.id(), UUID.randomUUID(), now());
        justPosted.reversed()[0] = true;
        everyCommitted.add(reversal);
        oracle.putAll(project(negate(delta(txn))));
        accepted++;
        assertThat(actualBalances())
                .as("a reversal must return every account to its previous balance")
                .isEqualTo(before);
    }

    // ── Invariants ──────────────────────────────────────────────────────────

    private void checkInvariants() {
        List<AccountBalance> balances = service.balances(tenantId);
        assertThat(balances).hasSize(accounts.size());
        for (AccountBalance b : balances) {
            assertThat(BigInteger.valueOf(b.netDebitMinor()))
                    .as("balance of %s must equal the oracle (no silent overflow)", b.accountId())
                    .isEqualTo(oracle.get(b.accountId()));
            long expectedSigned = b.type().normalSide() == Side.DEBIT ? b.netDebitMinor() : -b.netDebitMinor();
            assertThat(b.signedMinor()).isEqualTo(expectedSigned);
        }
        assertThat(service.trialBalance(tenantId))
                .as("debits minus credits over the whole tenant must be zero")
                .isEqualTo(BigInteger.ZERO);
    }

    private void checkEveryCommittedTransactionIsBalanced() {
        for (LedgerTransaction expected : everyCommitted) {
            LedgerTransaction stored = repository.findTransaction(tenantId, expected.id()).orElseThrow();
            assertThat(stored.entries().size()).isGreaterThanOrEqualTo(2);
            assertThat(stored.debitTotalMinor()).isEqualTo(stored.creditTotalMinor());
            assertThat(stored.entries()).containsExactlyInAnyOrderElementsOf(expected.entries());
        }
    }

    // ── Oracle helpers (BigInteger, independent of LedgerTransaction) ───────

    private Map<UUID, BigInteger> delta(LedgerTransaction txn) {
        Map<UUID, BigInteger> delta = new HashMap<>();
        for (LedgerEntry e : txn.entries()) {
            BigInteger amount = BigInteger.valueOf(e.amountMinor());
            delta.merge(e.accountId(), e.side() == Side.DEBIT ? amount : amount.negate(), BigInteger::add);
        }
        return delta;
    }

    private static Map<UUID, BigInteger> negate(Map<UUID, BigInteger> delta) {
        Map<UUID, BigInteger> negated = new HashMap<>();
        delta.forEach((k, v) -> negated.put(k, v.negate()));
        return negated;
    }

    /** The balances after applying the delta, or null if any would leave the allowed range. */
    private Map<UUID, BigInteger> project(Map<UUID, BigInteger> delta) {
        Map<UUID, BigInteger> after = new HashMap<>();
        for (Map.Entry<UUID, BigInteger> d : delta.entrySet()) {
            BigInteger next = oracle.get(d.getKey()).add(d.getValue());
            if (next.compareTo(MAX) > 0 || next.compareTo(MIN) < 0) {
                return null;
            }
            after.put(d.getKey(), next);
        }
        return after;
    }

    private Map<UUID, Long> actualBalances() {
        Map<UUID, Long> map = new HashMap<>();
        service.balances(tenantId).forEach(b -> map.put(b.accountId(), b.netDebitMinor()));
        return map;
    }

    private UUID id(int index) {
        return accounts.get(Math.floorMod(index, accounts.size())).id();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
