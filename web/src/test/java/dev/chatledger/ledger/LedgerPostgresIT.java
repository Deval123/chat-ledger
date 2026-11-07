package dev.chatledger.ledger;

import dev.chatledger.ledger.jdbc.JdbcLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ledger behaviour on a real PostgreSQL, running as the application role
 * (INSERT and SELECT only). Domain rules are checked in the domain and again,
 * independently, in the database: the "database" tests bypass the domain with
 * plain SQL to prove no code path can commit a ledger that breaks them.
 *
 * <p>Requirements: 4.1, 4.2, 4.3 (ledger data separate per tenant), 9.1. Task 2.1.
 */
class LedgerPostgresIT {

    private static final LedgerDatabase DB = LedgerDatabase.get();
    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private final JdbcLedgerRepository repository = new JdbcLedgerRepository(DB.appDataSource());
    private final LedgerService service = new LedgerService(repository);

    private UUID tenantA;
    private UUID tenantB;
    private Account receivableA;
    private Account revenueA;
    private Account receivableB;

    @BeforeEach
    void freshTenants() {
        tenantA = DB.createTenant();
        tenantB = DB.createTenant();
        receivableA = account(tenantA, "AR", AccountType.ASSET);
        revenueA = account(tenantA, "REV", AccountType.REVENUE);
        receivableB = account(tenantB, "AR", AccountType.ASSET);
    }

    private Account account(UUID tenant, String code, AccountType type) {
        Account a = new Account(UUID.randomUUID(), tenant, code, code + " account", type);
        repository.createAccount(a);
        return a;
    }

    private LedgerTransaction sale(long amount) {
        return new LedgerTransaction(UUID.randomUUID(), tenantA, "ORDER_ACCEPTED", "ORDER", UUID.randomUUID(),
                null, NOW, List.of(LedgerEntry.debit(receivableA.id(), amount), LedgerEntry.credit(revenueA.id(), amount)));
    }

    // ── 1. Posting works ────────────────────────────────────────────────────

    @Test
    @DisplayName("posting: a balanced transaction is stored and read back unchanged")
    void posting_works() {
        var txn = sale(5000);
        service.post(txn);

        var stored = repository.findTransaction(tenantA, txn.id()).orElseThrow();
        assertThat(stored.kind()).isEqualTo("ORDER_ACCEPTED");
        assertThat(stored.createdAt()).isEqualTo(NOW);
        assertThat(stored.reversesTxnId()).isNull();
        assertThat(stored.entries()).containsExactlyInAnyOrderElementsOf(txn.entries());

        assertThat(service.balances(tenantA)).anySatisfy(b -> {
            assertThat(b.accountId()).isEqualTo(receivableA.id());
            assertThat(b.netDebitMinor()).isEqualTo(5000);
            assertThat(b.signedMinor()).isEqualTo(5000);     // asset
        }).anySatisfy(b -> {
            assertThat(b.accountId()).isEqualTo(revenueA.id());
            assertThat(b.netDebitMinor()).isEqualTo(-5000);
            assertThat(b.signedMinor()).isEqualTo(5000);     // revenue
        });
        assertThat(service.trialBalance(tenantA)).isEqualTo(BigInteger.ZERO);
    }

    // ── 2. Unbalanced posting: domain and database ──────────────────────────

    @Test
    @DisplayName("unbalanced: refused by the domain before anything is written")
    void unbalanced_is_refused_by_the_domain() {
        assertThatThrownBy(() -> new LedgerTransaction(UUID.randomUUID(), tenantA, "X", "X", UUID.randomUUID(),
                null, NOW, List.of(LedgerEntry.debit(receivableA.id(), 100), LedgerEntry.credit(revenueA.id(), 90))))
                .isInstanceOf(LedgerException.class);
    }

    @Test
    @DisplayName("unbalanced: refused by the database at commit when the domain is bypassed")
    void unbalanced_is_refused_by_the_database() {
        UUID txn = UUID.randomUUID();
        assertThatThrownBy(() -> {
            try (Connection c = DB.appDataSource().getConnection()) {
                c.setAutoCommit(false);
                insertTxn(c, txn, tenantA, null);
                insertEntry(c, txn, receivableA.id(), 100, 0);
                insertEntry(c, txn, revenueA.id(), 0, 90);
                c.commit();
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23514");
            assertThat(e.getMessage()).contains("not balanced");
        });
        assertThat(repository.findTransaction(tenantA, txn)).as("nothing was committed").isEmpty();
    }

    @Test
    @DisplayName("unbalanced: a transaction with a single entry is refused by the database")
    void single_entry_is_refused_by_the_database() {
        UUID txn = UUID.randomUUID();
        assertThatThrownBy(() -> {
            try (Connection c = DB.appDataSource().getConnection()) {
                c.setAutoCommit(false);
                insertTxn(c, txn, tenantA, null);
                insertEntry(c, txn, receivableA.id(), 100, 0);
                c.commit();
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getMessage()).contains("at least two"));
        assertThat(repository.findTransaction(tenantA, txn)).isEmpty();
    }

    @Test
    @DisplayName("unbalanced: a transaction header with no entries is refused by the database")
    void header_without_entries_is_refused_by_the_database() {
        UUID txn = UUID.randomUUID();
        assertThatThrownBy(() -> {
            try (Connection c = DB.appDataSource().getConnection()) {
                c.setAutoCommit(false);
                insertTxn(c, txn, tenantA, null);
                c.commit();
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getMessage()).contains("at least two"));
        assertThat(repository.findTransaction(tenantA, txn)).isEmpty();
    }

    @Test
    @DisplayName("balance check is deferred: entries may arrive one by one inside a transaction")
    void balanced_plain_sql_is_accepted() throws SQLException {
        UUID txn = UUID.randomUUID();
        try (Connection c = DB.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            insertTxn(c, txn, tenantA, null);
            insertEntry(c, txn, receivableA.id(), 100, 0);   // not balanced yet, and that is fine
            insertEntry(c, txn, revenueA.id(), 0, 100);
            c.commit();
        }
        assertThat(repository.findTransaction(tenantA, txn)).isPresent();
    }

    // ── 3. Cross-tenant entries ─────────────────────────────────────────────

    @Test
    @DisplayName("tenant: an entry on another tenant's account is refused by the database")
    void cross_tenant_entry_is_refused_by_the_database() {
        UUID txn = UUID.randomUUID();
        assertThatThrownBy(() -> {
            try (Connection c = DB.appDataSource().getConnection()) {
                c.setAutoCommit(false);
                insertTxn(c, txn, tenantA, null);
                insertEntry(c, txn, receivableB.id(), 100, 0);   // tenant B's account in tenant A's txn
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23514");
            assertThat(e.getMessage()).contains("another tenant");
        });
    }

    @Test
    @DisplayName("tenant: the service refuses it too, and writes nothing")
    void cross_tenant_entry_is_refused_by_the_service() {
        var txn = new LedgerTransaction(UUID.randomUUID(), tenantA, "X", "X", UUID.randomUUID(), null, NOW,
                List.of(LedgerEntry.debit(receivableB.id(), 10), LedgerEntry.credit(revenueA.id(), 10)));

        assertThatThrownBy(() -> service.post(txn)).isInstanceOf(LedgerException.class);
        assertThat(repository.findTransaction(tenantA, txn.id())).isEmpty();
    }

    @Test
    @DisplayName("tenant: an account cannot be moved to another tenant, even by the owner")
    void account_cannot_change_tenant() {
        assertThatThrownBy(() -> {
            try (Connection c = DB.migrateDataSource().getConnection();
                 PreparedStatement ps = c.prepareStatement("UPDATE account SET tenant_id = ? WHERE id = ?")) {
                ps.setObject(1, tenantB);
                ps.setObject(2, receivableA.id());
                ps.executeUpdate();
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getMessage()).contains("cannot move"));
    }

    // ── 4. Reversal ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("reversal: restores every balance and leaves the original untouched")
    void reversal_restores_balances() {
        var original = sale(5000);
        service.post(original);

        var reversal = service.reverse(tenantA, original.id(), UUID.randomUUID(), NOW.plusSeconds(60));

        assertThat(service.balances(tenantA)).allSatisfy(b -> assertThat(b.netDebitMinor()).isZero());
        assertThat(repository.isReversed(tenantA, original.id())).isTrue();
        var storedReversal = repository.findTransaction(tenantA, reversal.id()).orElseThrow();
        assertThat(storedReversal.reversesTxnId()).isEqualTo(original.id());
        assertThat(storedReversal.entries()).extracting(LedgerEntry::side)
                .containsExactlyInAnyOrder(Side.CREDIT, Side.DEBIT);
        // Nothing was updated or deleted: the original reads back exactly as posted.
        assertThat(repository.findTransaction(tenantA, original.id()).orElseThrow().entries())
                .containsExactlyInAnyOrderElementsOf(original.entries());
    }

    @Test
    @DisplayName("reversal: works with the immutability triggers, which still refuse UPDATE and DELETE")
    void immutability_still_holds_after_a_reversal() {
        var original = sale(5000);
        service.post(original);
        service.reverse(tenantA, original.id(), UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> execute("UPDATE ledger_txn SET kind = 'TAMPERED' WHERE id = '" + original.id() + "'"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("DELETE FROM ledger_entry WHERE txn_id = '" + original.id() + "'"))
                .isInstanceOf(SQLException.class);
        assertThat(repository.findTransaction(tenantA, original.id())).isPresent();
    }

    // ── 5. A second reversal ────────────────────────────────────────────────

    @Test
    @DisplayName("second reversal: refused by the service")
    void second_reversal_is_refused_by_the_service() {
        var original = sale(5000);
        service.post(original);
        service.reverse(tenantA, original.id(), UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> service.reverse(tenantA, original.id(), UUID.randomUUID(), NOW))
                .isInstanceOf(AlreadyReversedException.class);
        assertThat(service.balances(tenantA)).allSatisfy(b -> assertThat(b.netDebitMinor()).isZero());
    }

    @Test
    @DisplayName("second reversal: refused by the database unique constraint when the service is bypassed")
    void second_reversal_is_refused_by_the_database() {
        var original = sale(5000);
        service.post(original);
        service.reverse(tenantA, original.id(), UUID.randomUUID(), NOW);

        // Calls the repository directly: no service pre-check, only the database to stop it.
        var second = original.reverse(UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> repository.append(second)).isInstanceOf(AlreadyReversedException.class);

        UUID rawTxn = UUID.randomUUID();
        assertThatThrownBy(() -> {
            try (Connection c = DB.appDataSource().getConnection()) {
                c.setAutoCommit(false);
                insertTxn(c, rawTxn, tenantA, original.id());
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23505");
            assertThat(e.getMessage()).contains("uq_ledger_txn_reverses");
        });
    }

    @Test
    @DisplayName("reversal: a reversal in another tenant is refused by the database")
    void reversal_across_tenants_is_refused_by_the_database() {
        var original = sale(5000);
        service.post(original);

        assertThatThrownBy(() -> {
            try (Connection c = DB.appDataSource().getConnection()) {
                c.setAutoCommit(false);
                insertTxn(c, UUID.randomUUID(), tenantB, original.id());   // tenant B "reverses" tenant A's txn
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23503");
            assertThat(e.getMessage()).contains("fk_ledger_txn_reverses_same_tenant");
        });
        assertThat(repository.isReversed(tenantA, original.id())).isFalse();
    }

    // ── 6. Cross-tenant reads ───────────────────────────────────────────────

    @Test
    @DisplayName("reads: another tenant sees nothing of this tenant's ledger")
    void cross_tenant_reads_return_nothing() {
        var original = sale(5000);
        service.post(original);
        service.reverse(tenantA, original.id(), UUID.randomUUID(), NOW);
        var other = sale(700);
        service.post(other);

        assertThat(repository.findTransaction(tenantB, other.id())).isEmpty();
        assertThat(repository.findAccount(tenantB, receivableA.id())).isEmpty();
        assertThat(repository.isReversed(tenantB, original.id())).isFalse();
        assertThat(repository.netDebit(tenantB, receivableA.id())).isZero();
        assertThat(service.balances(tenantB)).extracting(AccountBalance::accountId)
                .containsExactly(receivableB.id())
                .doesNotContain(receivableA.id(), revenueA.id());
        assertThat(service.balances(tenantB)).allSatisfy(b -> assertThat(b.netDebitMinor()).isZero());
        assertThatThrownBy(() -> service.reverse(tenantB, other.id(), UUID.randomUUID(), NOW))
                .isInstanceOf(LedgerException.class).hasMessageContaining("not found");
    }

    // ── Overflow ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("overflow: a posting that would overflow a balance is refused, not wrapped")
    void overflow_is_refused() {
        service.post(sale(Long.MAX_VALUE));

        assertThatThrownBy(() -> service.post(sale(1)))
                .isInstanceOf(LedgerException.class).hasMessageContaining("overflow");
        assertThat(repository.netDebit(tenantA, receivableA.id())).isEqualTo(Long.MAX_VALUE);
        assertThat(service.trialBalance(tenantA)).isEqualTo(BigInteger.ZERO);
    }

    // ── Helpers (plain SQL, bypassing the domain on purpose) ────────────────

    private void insertTxn(Connection c, UUID id, UUID tenant, UUID reverses) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id, reverses_txn_id) "
                        + "VALUES (?, ?, 'RAW', 'RAW', ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setObject(3, UUID.randomUUID());
            ps.setObject(4, reverses);
            ps.executeUpdate();
        }
    }

    private void insertEntry(Connection c, UUID txn, UUID account, long debit, long credit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, txn);
            ps.setObject(3, account);
            ps.setLong(4, debit);
            ps.setLong(5, credit);
            ps.executeUpdate();
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection c = DB.appDataSource().getConnection(); var s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
