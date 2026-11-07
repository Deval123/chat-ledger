package dev.chatledger.ledger;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests (jqwik 1.9.3) for the ledger domain. They run without
 * Docker against the in-memory repository. Amounts include 1 and values at or
 * near Long.MAX_VALUE. Requirement 4.1, 4.2, 4.4. Task 2.2.
 */
class LedgerPropertiesTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    // ── Every committed transaction is balanced; no silent overflow ─────────

    /** Arbitrary entries: the constructor accepts exactly the balanced ones, judged with BigInteger. */
    @Property(tries = 400)
    void a_transaction_is_accepted_if_and_only_if_it_is_balanced_and_fits(
            @ForAll("arbitraryEntries") List<LedgerEntry> entries) {
        BigInteger debits = sum(entries, Side.DEBIT);
        BigInteger credits = sum(entries, Side.CREDIT);
        BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
        boolean valid = entries.size() >= 2 && debits.equals(credits) && debits.compareTo(max) <= 0;

        if (valid) {
            var t = txn(entries);
            assertThat(BigInteger.valueOf(t.debitTotalMinor())).isEqualTo(debits);
            assertThat(BigInteger.valueOf(t.creditTotalMinor())).isEqualTo(credits);
        } else {
            assertThatThrownBy(() -> txn(entries)).isInstanceOf(LedgerException.class);
        }
    }

    /** Balanced by construction, with sides summing to exactly Long.MAX_VALUE some of the time. */
    @Property(tries = 300)
    void balanced_transactions_are_always_accepted_with_exact_totals(
            @ForAll("balancedEntries") List<LedgerEntry> entries) {
        var t = txn(entries);
        assertThat(t.debitTotalMinor()).isEqualTo(t.creditTotalMinor());
        assertThat(BigInteger.valueOf(t.debitTotalMinor())).isEqualTo(sum(entries, Side.DEBIT));
    }

    /**
     * Both sides add up to the same number, but that number is larger than
     * Long.MAX_VALUE. An implementation that wraps around would see equal
     * (wrapped) totals and accept it. Random lists almost never produce this
     * case, so it is generated on purpose. Found by mutation testing.
     */
    @Property(tries = 300)
    void sides_that_are_equal_only_after_wrapping_around_are_rejected(
            @ForAll("overflowingButEqualSides") List<LedgerEntry> entries) {
        assertThat(sum(entries, Side.DEBIT)).isEqualTo(sum(entries, Side.CREDIT))
                .isGreaterThan(BigInteger.valueOf(Long.MAX_VALUE));
        assertThatThrownBy(() -> txn(entries))
                .isInstanceOf(LedgerException.class).hasMessageContaining("overflows");
    }

    // ── Reversal ────────────────────────────────────────────────────────────

    /** Post then reverse: every account returns to the balance it had before. */
    @Property(tries = 300)
    void a_reversal_returns_every_account_to_its_previous_balance(
            @ForAll("balancedEntries") List<LedgerEntry> entries) {
        var fixture = new Fixture(3);
        var rebased = fixture.onAccounts(entries);
        var before = fixture.netDebits();

        fixture.service.post(rebased);
        fixture.service.reverse(fixture.tenant, rebased.id(), UUID.randomUUID(), NOW);

        assertThat(fixture.netDebits()).isEqualTo(before);
    }

    @Property(tries = 200)
    void reversing_twice_is_impossible(@ForAll("balancedEntries") List<LedgerEntry> entries) {
        var fixture = new Fixture(3);
        var rebased = fixture.onAccounts(entries);
        fixture.service.post(rebased);
        fixture.service.reverse(fixture.tenant, rebased.id(), UUID.randomUUID(), NOW);
        var afterFirst = fixture.netDebits();

        assertThatThrownBy(() -> fixture.service.reverse(fixture.tenant, rebased.id(), UUID.randomUUID(), NOW))
                .isInstanceOf(AlreadyReversedException.class);
        assertThat(fixture.netDebits()).isEqualTo(afterFirst);
    }

    // ── Sequences of postings and reversals ─────────────────────────────────

    /**
     * Random sequences keep every invariant: each committed transaction is
     * balanced, the net balances of a tenant sum to zero, balances equal the
     * BigInteger oracle (so nothing overflowed silently), a reversal undoes its
     * original, and reversing twice is refused.
     */
    @Property(tries = 300)
    void random_sequences_of_postings_and_reversals_keep_every_invariant(
            @ForAll("operations") List<LedgerScenario.Op> ops) {
        var repository = new InMemoryLedgerRepository();
        var tenant = UUID.randomUUID();
        var accounts = LedgerScenario.createAccounts(repository, tenant);
        new LedgerScenario(new LedgerService(repository), repository, tenant, accounts).run(ops);
    }

    /** Tenants never see each other's data, whatever was posted. */
    @Property(tries = 100)
    void another_tenant_never_sees_balances_or_transactions(
            @ForAll("operations") List<LedgerScenario.Op> ops) {
        var repository = new InMemoryLedgerRepository();
        var tenantA = UUID.randomUUID();
        var tenantB = UUID.randomUUID();
        var accountsA = LedgerScenario.createAccounts(repository, tenantA);
        var accountsB = LedgerScenario.createAccounts(repository, tenantB);
        var service = new LedgerService(repository);
        new LedgerScenario(service, repository, tenantA, accountsA).run(ops);

        assertThat(service.balances(tenantB)).hasSize(accountsB.size())
                .allSatisfy(b -> assertThat(b.netDebitMinor()).isZero());
        assertThat(service.trialBalance(tenantB)).isEqualTo(BigInteger.ZERO);
    }

    // ── Generators ──────────────────────────────────────────────────────────

    @Provide
    Arbitrary<List<LedgerScenario.Op>> operations() {
        return LedgerGenerators.operations(4, 20);
    }

    /** 0 to 6 entries with random sides over three account slots; mostly unbalanced. */
    @Provide
    Arbitrary<List<LedgerEntry>> arbitraryEntries() {
        Arbitrary<LedgerEntry> entry = net.jqwik.api.Combinators.combine(
                Arbitraries.integers().between(0, 2),
                Arbitraries.of(Side.class),
                LedgerGenerators.amounts())
                .as((slot, side, amount) -> new LedgerEntry(UUID.randomUUID(), slotId(slot), side, amount));
        // Mix in balanced candidates and balanced-but-overflowing ones, so both are common.
        return Arbitraries.oneOf(entry.list().ofMaxSize(6), balancedEntries(), overflowingButEqualSides());
    }

    /** Debits a + b and credits MAX + (a + b - MAX): equal sums that exceed Long.MAX_VALUE. */
    @Provide
    Arbitrary<List<LedgerEntry>> overflowingButEqualSides() {
        long half = Long.MAX_VALUE / 2 + 1;       // two of these already exceed Long.MAX_VALUE
        Arbitrary<Long> big = Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.just(half)),
                Tuple.of(1, Arbitraries.just(Long.MAX_VALUE)),
                Tuple.of(3, Arbitraries.longs().between(half, Long.MAX_VALUE)));
        return net.jqwik.api.Combinators.combine(big, big).as((a, b) -> {
            long rest = BigInteger.valueOf(a).add(BigInteger.valueOf(b))
                    .subtract(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
            return List.of(
                    new LedgerEntry(UUID.randomUUID(), slotId(0), Side.DEBIT, a),
                    new LedgerEntry(UUID.randomUUID(), slotId(1), Side.DEBIT, b),
                    new LedgerEntry(UUID.randomUUID(), slotId(2), Side.CREDIT, Long.MAX_VALUE),
                    new LedgerEntry(UUID.randomUUID(), slotId(0), Side.CREDIT, rest));
        });
    }

    /** One side made of 1 to 4 parts, the other side a single entry for their sum. */
    @Provide
    Arbitrary<List<LedgerEntry>> balancedEntries() {
        Arbitrary<List<Long>> parts = Arbitraries.frequencyOf(
                Tuple.of(3, Arbitraries.of(1L, Long.MAX_VALUE / 4, Long.MAX_VALUE / 4 - 1).list().ofMinSize(1).ofMaxSize(4)),
                Tuple.of(3, Arbitraries.longs().between(1L, 1_000_000L).list().ofMinSize(1).ofMaxSize(4)),
                Tuple.of(1, Arbitraries.just(List.of(Long.MAX_VALUE))),
                Tuple.of(1, Arbitraries.just(List.of(1L))));
        return net.jqwik.api.Combinators.combine(parts, Arbitraries.of(Side.class), Arbitraries.integers().between(0, 2))
                .as((list, side, slot) -> {
                    List<LedgerEntry> entries = new ArrayList<>();
                    long total = 0;
                    for (int i = 0; i < list.size(); i++) {
                        entries.add(new LedgerEntry(UUID.randomUUID(), slotId(i % 3), side, list.get(i)));
                        total += list.get(i);          // at most 4 * MAX/4 = MAX: cannot overflow
                    }
                    entries.add(new LedgerEntry(UUID.randomUUID(), slotId(slot), side.opposite(), total));
                    return entries;
                });
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static final UUID[] SLOTS = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};

    private static UUID slotId(int slot) {
        return SLOTS[slot];
    }

    private static LedgerTransaction txn(List<LedgerEntry> entries) {
        return new LedgerTransaction(UUID.randomUUID(), UUID.randomUUID(), "TEST", "TEST", UUID.randomUUID(),
                null, NOW, entries);
    }

    private static BigInteger sum(List<LedgerEntry> entries, Side side) {
        return entries.stream().filter(e -> e.side() == side)
                .map(e -> BigInteger.valueOf(e.amountMinor())).reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** A tenant with three accounts, matching the three slots used by the generators. */
    private static final class Fixture {
        final InMemoryLedgerRepository repository = new InMemoryLedgerRepository();
        final LedgerService service = new LedgerService(repository);
        final UUID tenant = UUID.randomUUID();
        final List<Account> accounts = new ArrayList<>();

        Fixture(int count) {
            AccountType[] types = {AccountType.ASSET, AccountType.LIABILITY, AccountType.REVENUE};
            for (int i = 0; i < count; i++) {
                var a = new Account(UUID.randomUUID(), tenant, "A" + i, "Account " + i, types[i]);
                repository.createAccount(a);
                accounts.add(a);
            }
        }

        /** The same entries re-pointed from the generator's slots to this tenant's accounts. */
        LedgerTransaction onAccounts(List<LedgerEntry> entries) {
            List<LedgerEntry> mapped = entries.stream().map(e -> {
                int slot = java.util.Arrays.asList(SLOTS).indexOf(e.accountId());
                return new LedgerEntry(e.id(), accounts.get(slot).id(), e.side(), e.amountMinor());
            }).toList();
            return new LedgerTransaction(UUID.randomUUID(), tenant, "TEST", "TEST", UUID.randomUUID(),
                    null, NOW, mapped);
        }

        List<Long> netDebits() {
            return service.balances(tenant).stream().map(AccountBalance::netDebitMinor).toList();
        }
    }
}
