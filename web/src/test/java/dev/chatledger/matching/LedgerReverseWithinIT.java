package dev.chatledger.matching;

import dev.chatledger.ledger.AlreadyReversedException;
import dev.chatledger.ledger.LedgerException;
import dev.chatledger.ledger.LedgerTransaction;
import dev.chatledger.ledger.jdbc.JdbcLedgerRepository;
import dev.chatledger.tenancy.TenantScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static dev.chatledger.matching.MatchingFixture.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code JdbcLedgerRepository.reverseWithin}: the reversal of a posting on the caller's connection, so that it commits or rolls back
 * with whatever else the caller wrote. The original is never touched; the reversal is its mirror; a posting is reversed at most once,
 * also when sixteen threads try at the same moment.
 *
 * <p>Requirements: 4.2, 4.3. Task 6.3.
 */
class LedgerReverseWithinIT {

    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final TenantScope scope = new TenantScope(MatchWorld.DB.appDataSource());
    private final JdbcLedgerRepository ledger = new JdbcLedgerRepository(scope);

    /** An accepted order of 5,000 and its posting, committed. */
    private LedgerTransaction posted(UUID forTenant) throws Exception {
        TenantScope s = new TenantScope(MatchWorld.DB.appDataSource());
        ChartOfAccounts.Accounts accounts = s.call(forTenant, c -> ChartOfAccounts.ensure(c, forTenant));
        LedgerTransaction txn = Postings.orderAccepted(forTenant, UUID.randomUUID(), 5000, accounts, Instant.now());
        new JdbcLedgerRepository(s).append(txn);
        return txn;
    }

    @Test
    void the_reversal_is_the_mirror_of_the_original_which_is_left_alone() throws Exception {
        LedgerTransaction original = posted(tenant);
        UUID reversalId = UUID.randomUUID();
        Instant at = Instant.now().plusNanos(799);   // nanoseconds, which PostgreSQL would round

        LedgerTransaction reversal = scope.call(tenant, c -> ledger.reverseWithin(c, tenant, original.id(), reversalId, at));

        assertThat(reversal.createdAt()).as("the reversal returned carries the time that was stored").isEqualTo(dev.chatledger.tenancy.DbTime.micros(at));
        assertThat(MatchingFixture.rows("SELECT created_at::text FROM ledger_txn WHERE tenant_id = ? AND kind = 'REVERSAL'", tenant)).hasSize(1);
        assertThat(reversal.id()).isEqualTo(reversalId);
        assertThat(reversal.reversesTxnId()).isEqualTo(original.id());
        assertThat(reversal.kind()).isEqualTo("REVERSAL");
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000", "REVERSAL 1100 C5000", "REVERSAL 4000 D5000");
        assertThat(ledger.findTransaction(tenant, original.id()).orElseThrow().entries()).hasSize(2);
        assertThat(ledger.isReversed(tenant, original.id())).isTrue();
        assertThat(MatchingFixture.trialBalance(tenant)).isZero();
    }

    @Test
    void the_reversal_rolls_back_with_whatever_else_the_caller_wrote() throws Exception {
        LedgerTransaction original = posted(tenant);
        assertThatThrownBy(() -> scope.call(tenant, c -> {
            ledger.reverseWithin(c, tenant, original.id(), UUID.randomUUID(), Instant.now());
            MatchWorld.run(c, "INSERT INTO audit_log(id, tenant_id, action, subject) VALUES (?, ?, 'MARKER', 'x')", UUID.randomUUID(), tenant);
            throw new IllegalStateException("the caller fails after the reversal");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("the caller fails");
        assertThat(ledger.isReversed(tenant, original.id())).isFalse();
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND kind = 'REVERSAL'", tenant, null)).isZero();
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'MARKER'", tenant, null)).isZero();
        // and the same call, without the failure, works
        scope.call(tenant, c -> ledger.reverseWithin(c, tenant, original.id(), UUID.randomUUID(), Instant.now()));
        assertThat(ledger.isReversed(tenant, original.id())).isTrue();
    }

    @Test
    void a_reversal_of_nothing_of_a_reversal_of_another_tenants_posting_and_a_second_reversal_are_refused() throws Exception {
        LedgerTransaction original = posted(tenant);
        MatchWorld other = new MatchWorld();
        LedgerTransaction foreign = posted(other.tenant);
        LedgerTransaction reversal = scope.call(tenant, c -> ledger.reverseWithin(c, tenant, original.id(), UUID.randomUUID(), Instant.now()));

        assertThatThrownBy(() -> scope.call(tenant, c -> ledger.reverseWithin(c, tenant, UUID.randomUUID(), UUID.randomUUID(), Instant.now())))
                .isInstanceOf(LedgerException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> scope.call(tenant, c -> ledger.reverseWithin(c, tenant, foreign.id(), UUID.randomUUID(), Instant.now())))
                .as("another tenant's posting").isInstanceOf(LedgerException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> scope.call(tenant, c -> ledger.reverseWithin(c, tenant, reversal.id(), UUID.randomUUID(), Instant.now())))
                .as("a reversal").isInstanceOf(LedgerException.class).hasMessageContaining("reversal");
        assertThatThrownBy(() -> scope.call(tenant, c -> ledger.reverseWithin(c, tenant, original.id(), UUID.randomUUID(), Instant.now())))
                .as("twice").isInstanceOf(AlreadyReversedException.class);
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND kind = 'REVERSAL'", tenant, null)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND kind = 'REVERSAL'", other.tenant, null)).as("the other tenant is untouched").isZero();
    }

    @Test
    void a_refused_second_reversal_leaves_the_callers_transaction_usable() throws Exception {
        LedgerTransaction original = posted(tenant);
        scope.call(tenant, c -> ledger.reverseWithin(c, tenant, original.id(), UUID.randomUUID(), Instant.now()));

        scope.call(tenant, c -> {
            assertThatThrownBy(() -> ledger.reverseWithin(c, tenant, original.id(), UUID.randomUUID(), Instant.now())).isInstanceOf(AlreadyReversedException.class);
            // the refusal came before any write, so the database did not abort the transaction: the caller can go on and commit
            MatchWorld.run(c, "INSERT INTO audit_log(id, tenant_id, action, subject) VALUES (?, ?, 'MARKER', 'x')", UUID.randomUUID(), tenant);
            return null;
        });

        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'MARKER'", tenant, null)).isEqualTo(1);
    }

    @Test
    void sixteen_threads_reversing_one_posting_make_exactly_one_reversal() throws Exception {
        LedgerTransaction original = posted(tenant);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            results.add(pool.submit(() -> {
                go.await();
                try {
                    scope.call(tenant, c -> ledger.reverseWithin(c, tenant, original.id(), UUID.randomUUID(), Instant.now()));
                    return "REVERSED";
                } catch (AlreadyReversedException e) {
                    return "ALREADY";
                }
            }));
        }
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> r : results) {
            outcomes.add(r.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(outcomes).filteredOn("REVERSED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("ALREADY"::equals).hasSize(15);
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND kind = 'REVERSAL'", tenant, null)).isEqualTo(1);
        assertThat(MatchingFixture.trialBalance(tenant)).isZero();
    }
}
