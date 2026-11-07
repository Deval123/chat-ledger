package dev.chatledger.tenancy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.chatledger.ledger.LedgerDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static dev.chatledger.tenancy.TenantSeed.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V011 and {@link TenantScope} on the real ledger tables: the policies fail
 * closed, the tenant never outlives its transaction, and the deferred balance
 * trigger and the tenant trigger still do their job under row-level security.
 *
 * <p>Task 3.2.
 */
class RowLevelSecurityIT {

    private static final LedgerDatabase DB = LedgerDatabase.get();
    private static TenantSeed a;
    private static TenantSeed b;
    private static TenantScope scope;

    @BeforeAll
    static void seed() {
        a = TenantSeed.create(DB.migrateDataSource());
        b = TenantSeed.create(DB.migrateDataSource());
        scope = new TenantScope(DB.appDataSource());
    }

    private static long countIn(TenantScope s, UUID tenant, String table) throws SQLException {
        return s.call(tenant, c -> count(c, table));
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ── Fails closed ────────────────────────────────────────────────────────

    @Test
    void without_a_tenant_the_application_role_sees_and_writes_nothing() throws SQLException {
        try (Connection c = DB.appDataSource().getConnection()) {
            for (String table : new String[]{"account", "ledger_txn", "ledger_entry", "orders", "customer", "tenant"}) {
                assertThat(count(c, table)).as(table + " rows visible with no tenant").isZero();
            }
            assertThatThrownBy(() -> update(c,
                    "INSERT INTO account(id, tenant_id, code, name, type) VALUES (?, ?, 'X', 'x', 'ASSET')",
                    UUID.randomUUID(), a.tenantId()))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void a_tenant_set_outside_a_transaction_does_not_stick_so_it_still_fails_closed() throws SQLException {
        try (Connection c = DB.appDataSource().getConnection()) {   // autocommit: every statement is its own transaction
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, a.tenantId().toString());
                ps.execute();
            }
            assertThat(count(c, "account")).as("the setting died with its one-statement transaction").isZero();
        }
    }

    @Test
    void a_value_that_is_not_a_uuid_is_an_error_not_a_match() throws SQLException {
        try (Connection c = DB.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', 'not-a-uuid', true)")) {
                ps.execute();
            }
            assertThatThrownBy(() -> count(c, "account")).isInstanceOf(SQLException.class);
            c.rollback();
        }
    }

    // ── Scoped access ───────────────────────────────────────────────────────

    @Test
    void a_tenant_scope_sees_only_its_own_rows_in_every_ledger_table() throws SQLException {
        assertThat(countIn(scope, a.tenantId(), "account")).isEqualTo(2);
        assertThat(countIn(scope, a.tenantId(), "ledger_txn")).isEqualTo(1);
        assertThat(countIn(scope, a.tenantId(), "ledger_entry")).isEqualTo(2);
        assertThat(countIn(scope, b.tenantId(), "ledger_entry")).isEqualTo(2);
    }

    @Test
    void the_tenant_table_shows_only_the_current_tenant_and_cannot_be_written() throws SQLException {
        assertThat(countIn(scope, a.tenantId(), "tenant")).isEqualTo(1);
        assertThatThrownBy(() -> scope.call(a.tenantId(), c -> {
            update(c, "INSERT INTO tenant(id, name, currency, scale, slug) VALUES (?, 'n', 'XAF', 0, ?)",
                    UUID.randomUUID(), "made-by-app-" + UUID.randomUUID().toString().substring(0, 8));
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    @Test
    void the_owner_is_not_subject_to_the_policies_which_is_a_named_trade_off() throws SQLException {
        // ENABLE, not FORCE: the owner (Flyway only) sees every tenant. The application role never is the owner.
        try (Connection c = DB.migrateDataSource().getConnection()) {
            assertThat(count(c, "account")).isGreaterThanOrEqualTo(4);
        }
    }

    // ── No tenant leaks through a pooled connection ─────────────────────────

    @Test
    void a_pooled_connection_carries_no_tenant_to_its_next_borrower() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setDataSource(DB.appDataSource());
        config.setMaximumPoolSize(1);          // the same physical connection is reused every time
        config.setPoolName("single-connection-pool");
        try (HikariDataSource pool = new HikariDataSource(config)) {
            TenantScope pooled = new TenantScope(pool);
            int pidInScope = pooled.call(a.tenantId(), c -> {
                assertThat(count(c, "account")).isEqualTo(2);
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT pg_backend_pid()")) {
                    rs.next();
                    return rs.getInt(1);
                }
            });

            try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
                try (ResultSet rs = s.executeQuery("SELECT pg_backend_pid()")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("same physical connection").isEqualTo(pidInScope);
                }
                try (ResultSet rs = s.executeQuery("SELECT current_setting('app.tenant_id', true)")) {
                    rs.next();
                    String leftover = rs.getString(1);
                    assertThat(leftover == null || leftover.isEmpty()).as("tenant left on the connection: %s", leftover).isTrue();
                }
                assertThat(count(c, "account")).as("the next borrower sees nothing").isZero();
            }

            // And another tenant on the same connection sees only its own rows.
            assertThat(countIn(pooled, b.tenantId(), "account")).isEqualTo(2);
        }
    }

    @Test
    void a_failed_scope_rolls_back_and_leaves_no_tenant_behind() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setDataSource(DB.appDataSource());
        config.setMaximumPoolSize(1);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            TenantScope pooled = new TenantScope(pool);
            assertThatThrownBy(() -> pooled.call(a.tenantId(), c -> {
                throw new SQLException("boom");
            })).isInstanceOf(SQLException.class).hasMessage("boom");
            try (Connection c = pool.getConnection()) {
                assertThat(count(c, "account")).isZero();
            }
        }
    }

    // ── The ledger triggers under row-level security ────────────────────────

    @Test
    void the_deferred_balance_trigger_accepts_a_balanced_transaction_under_rls() throws SQLException {
        UUID txn = UUID.randomUUID();
        scope.call(a.tenantId(), c -> {
            insertTxn(c, txn, a.tenantId());
            insertEntry(c, txn, a.receivableAccountId(), 700, 0);
            insertEntry(c, txn, a.revenueAccountId(), 0, 700);
            return null;
        });
        long entries = scope.call(a.tenantId(), c -> countWhere(c, "ledger_entry", "txn_id", txn));
        assertThat(entries).isEqualTo(2);
    }

    @Test
    void the_deferred_balance_trigger_still_refuses_an_unbalanced_transaction_under_rls() throws SQLException {
        UUID txn = UUID.randomUUID();
        assertThatThrownBy(() -> scope.call(a.tenantId(), c -> {
            insertTxn(c, txn, a.tenantId());
            insertEntry(c, txn, a.receivableAccountId(), 700, 0);
            insertEntry(c, txn, a.revenueAccountId(), 0, 600);
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23514");
            // "not balanced", not "at least two entries": the trigger saw both entries through RLS at commit,
            // so the tenant setting was still readable when it fired.
            assertThat(e.getMessage()).contains("not balanced");
        });
        assertThat(ownerCount("ledger_txn", txn)).as("nothing was committed").isZero();
    }

    @Test
    void an_entry_on_another_tenants_account_is_refused_because_it_is_invisible() {
        UUID txn = UUID.randomUUID();
        assertThatThrownBy(() -> scope.call(a.tenantId(), c -> {
            insertTxn(c, txn, a.tenantId());
            insertEntry(c, txn, b.receivableAccountId(), 5, 0);   // tenant B's account, invisible to tenant A
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23514");
            assertThat(e.getMessage()).contains("another tenant");
        });
    }

    @Test
    void the_owner_who_sees_both_tenants_is_refused_too() {
        // Without row-level security hiding it, the same entry is refused for the visible mismatch (the V009 behaviour).
        UUID txn = UUID.randomUUID();
        assertThatThrownBy(() -> {
            try (Connection c = DB.migrateDataSource().getConnection()) {
                c.setAutoCommit(false);
                insertTxn(c, txn, a.tenantId());
                insertEntry(c, txn, b.receivableAccountId(), 5, 0);
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getMessage()).contains("another tenant"));
    }

    @Test
    void audit_log_is_insert_and_select_only_for_the_application() throws SQLException {
        scope.call(a.tenantId(), c -> {
            update(c, "INSERT INTO audit_log(id, tenant_id, actor, action, subject) VALUES (?, ?, ?, 'LOGIN', 'x')",
                    UUID.randomUUID(), a.tenantId(), a.userId());
            return null;
        });
        assertThatThrownBy(() -> scope.call(a.tenantId(), c -> {
            update(c, "UPDATE audit_log SET action = 'TAMPERED'");
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        assertThatThrownBy(() -> scope.call(a.tenantId(), c -> {
            update(c, "DELETE FROM audit_log");
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static void insertTxn(Connection c, UUID txn, UUID tenant) throws SQLException {
        update(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'TEST', 'TEST', ?)",
                txn, tenant, UUID.randomUUID());
    }

    private static void insertEntry(Connection c, UUID txn, UUID account, long debit, long credit) throws SQLException {
        update(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), txn, account, debit, credit);
    }

    private static long countWhere(Connection c, String table, String column, UUID value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE " + column + " = ?")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long ownerCount(String table, UUID txn) throws SQLException {
        try (Connection c = DB.migrateDataSource().getConnection()) {
            return countWhere(c, table, "id", txn);
        }
    }
}
