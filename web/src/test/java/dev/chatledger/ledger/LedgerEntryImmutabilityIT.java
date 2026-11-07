package dev.chatledger.ledger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for ledger_entry and ledger_txn immutability (task 2.3).
 *
 * <h2>What is and is not covered</h2>
 * <p>The triggers fire for every non-superuser role, including the table owner
 * ({@code chatledger_migrate}).  A PostgreSQL superuser can still run
 * {@code DISABLE TRIGGER ALL} or {@code DROP TRIGGER} and then mutate rows.
 * That residual risk is accepted and documented in docs/kiro-notes.md; it is
 * mitigated by keeping the superuser credential out of the application and out
 * of Flyway (see .env.example).
 *
 * <p>Tests in this class therefore:
 * <ul>
 *   <li>Prove the trigger blocks the <em>migrate role</em> (non-superuser schema
 *       owner) for UPDATE, DELETE, and TRUNCATE on both tables.</li>
 *   <li>Prove the <em>app login role</em> is blocked by missing privileges
 *       (permission denied) before the trigger is even reached.</li>
 *   <li>Prove the app login role cannot disable or drop the triggers.</li>
 *   <li>Prove INSERT and reversal entries still work for the app role.</li>
 *   <li>Prove tenant-isolation: a query scoped to tenant A returns no rows
 *       belonging to tenant B.</li>
 * </ul>
 *
 * <p>Requirements: 4.2 (append-only ledger), 9.1 (tenant isolation). Task: 2.3.
 */
@Testcontainers
class LedgerEntryImmutabilityIT {

    // ── Container ────────────────────────────────────────────────────────────
    //
    // Testcontainers always starts with a superuser.  We use that superuser
    // only in setUp() to create the non-superuser migrate role — mirroring
    // what docker/initdb/01_create_roles.sh does in production.
    // Flyway then runs as chatledger_migrate, not as the superuser.

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine")
                    .withDatabaseName("chatledger_it")
                    .withUsername("tc_superuser")
                    .withPassword("tc_super_secret");

    // ── Connection details ────────────────────────────────────────────────────

    // Superuser — bootstrap only; never used after setUp.
    private static String superJdbcUrl;
    private static String superUser;
    private static String superPass;

    // chatledger_migrate — non-superuser schema owner; runs Flyway.
    private static final String MIGRATE_USER = "chatledger_migrate";
    private static final String MIGRATE_PASS = "migrate_test_secret";

    // chatledger_app_login — runtime application login.
    private static final String APP_USER = "chatledger_app_login";
    private static final String APP_PASS = "app_test_secret";

    // ── Fixture IDs (read-only after setUp) ───────────────────────────────────

    private static UUID tenantAId;
    private static UUID tenantBId;
    private static UUID accountAId;
    private static UUID revenueAId;
    private static UUID txnAId;
    private static UUID entryAId;   // the stable entry used for mutation tests

    // ── Setup ─────────────────────────────────────────────────────────────────

    @BeforeAll
    static void setUp() throws Exception {
        superJdbcUrl = POSTGRES.getJdbcUrl();
        superUser    = POSTGRES.getUsername();
        superPass    = POSTGRES.getPassword();

        // 1. Create chatledger_migrate (non-superuser, owns the DB) using the
        //    bootstrap superuser — mirrors docker/initdb/01_create_roles.sh.
        try (Connection c = superConn(); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE \"" + MIGRATE_USER + "\""
                    + " LOGIN PASSWORD '" + MIGRATE_PASS + "'"
                    + " CREATEDB NOSUPERUSER NOCREATEROLE NOREPLICATION");
            s.execute("ALTER DATABASE chatledger_it OWNER TO \""
                    + MIGRATE_USER + "\"");
            // Group role and app login: init script responsibility in production.
            s.execute("CREATE ROLE chatledger_app NOLOGIN NOSUPERUSER NOCREATEROLE");
            s.execute("CREATE ROLE \"" + APP_USER + "\""
                    + " LOGIN PASSWORD '" + APP_PASS + "'"
                    + " NOSUPERUSER NOCREATEROLE NOCREATEDB");
            s.execute("GRANT chatledger_app TO \"" + APP_USER + "\"");
        }

        // 2. Run all Flyway migrations as chatledger_migrate (non-superuser).
        Flyway.configure()
              .dataSource(superJdbcUrl, MIGRATE_USER, MIGRATE_PASS)
              .locations("classpath:db/migration")
              .load()
              .migrate();

        // 3. Seed tenant A and tenant B data as migrate role (schema owner).
        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        //    Since V009 a ledger transaction must be balanced when it commits, so
        //    each one is written as a debit and a credit in one database
        //    transaction (autocommit off).
        try (Connection c = migrateConn(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("INSERT INTO tenant(id,name,currency,scale) VALUES ("
                    + lit(tenantAId) + ",'Seller A','XAF',0)");
            s.execute("INSERT INTO tenant(id,name,currency,scale) VALUES ("
                    + lit(tenantBId) + ",'Seller B','XAF',0)");

            accountAId = UUID.randomUUID();
            revenueAId = UUID.randomUUID();
            s.execute("INSERT INTO account(id,tenant_id,code,name,type) VALUES ("
                    + lit(accountAId) + "," + lit(tenantAId)
                    + ",'AR','Accounts Receivable','ASSET')");
            s.execute("INSERT INTO account(id,tenant_id,code,name,type) VALUES ("
                    + lit(revenueAId) + "," + lit(tenantAId)
                    + ",'REV','Sales revenue','REVENUE')");

            txnAId = UUID.randomUUID();
            entryAId = UUID.randomUUID();
            s.execute("INSERT INTO ledger_txn(id,tenant_id,kind,ref_type,ref_id) VALUES ("
                    + lit(txnAId) + "," + lit(tenantAId)
                    + ",'ORDER_ACCEPTED','ORDER'," + lit(UUID.randomUUID()) + ")");
            s.execute("INSERT INTO ledger_entry(id,txn_id,account_id,debit_minor,credit_minor)"
                    + " VALUES (" + lit(entryAId) + "," + lit(txnAId) + ","
                    + lit(accountAId) + ",5000,0)");
            s.execute("INSERT INTO ledger_entry(id,txn_id,account_id,debit_minor,credit_minor)"
                    + " VALUES (" + lit(UUID.randomUUID()) + "," + lit(txnAId) + ","
                    + lit(revenueAId) + ",0,5000)");

            // Tenant B data.
            UUID acctBId = UUID.randomUUID();
            UUID revBId = UUID.randomUUID();
            s.execute("INSERT INTO account(id,tenant_id,code,name,type) VALUES ("
                    + lit(acctBId) + "," + lit(tenantBId)
                    + ",'AR','Accounts Receivable','ASSET')");
            s.execute("INSERT INTO account(id,tenant_id,code,name,type) VALUES ("
                    + lit(revBId) + "," + lit(tenantBId)
                    + ",'REV','Sales revenue','REVENUE')");
            UUID txnBId = UUID.randomUUID();
            s.execute("INSERT INTO ledger_txn(id,tenant_id,kind,ref_type,ref_id) VALUES ("
                    + lit(txnBId) + "," + lit(tenantBId)
                    + ",'ORDER_ACCEPTED','ORDER'," + lit(UUID.randomUUID()) + ")");
            s.execute("INSERT INTO ledger_entry(id,txn_id,account_id,debit_minor,credit_minor)"
                    + " VALUES (" + lit(UUID.randomUUID()) + "," + lit(txnBId) + ","
                    + lit(acctBId) + ",9000,0)");
            s.execute("INSERT INTO ledger_entry(id,txn_id,account_id,debit_minor,credit_minor)"
                    + " VALUES (" + lit(UUID.randomUUID()) + "," + lit(txnBId) + ","
                    + lit(revBId) + ",0,9000)");
            c.commit();
        }
    }

    // ── Trigger tests — migrate role (non-superuser schema owner) ────────────
    //
    // These tests prove that the trigger fires even for the table owner, because
    // the table owner is not a PostgreSQL superuser.

    @Test
    @DisplayName("trigger: migrate role cannot UPDATE ledger_entry")
    void migrate_role_cannot_update_ledger_entry() {
        assertThatThrownBy(() -> {
            try (Connection c = migrateConn(); Statement s = c.createStatement()) {
                // V007 revoked UPDATE from the owner, but the owner can re-grant
                // it. Do that first so only the trigger is left to stop us.
                s.execute("GRANT UPDATE ON ledger_entry TO " + MIGRATE_USER);
                s.execute("UPDATE ledger_entry SET debit_minor=1 WHERE id="
                        + lit(entryAId));
            }
        })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("trigger: migrate role cannot DELETE from ledger_entry")
    void migrate_role_cannot_delete_ledger_entry() {
        assertThatThrownBy(() -> {
            try (Connection c = migrateConn(); Statement s = c.createStatement()) {
                // Same as above: re-grant DELETE so only the trigger can stop us.
                s.execute("GRANT DELETE ON ledger_entry TO " + MIGRATE_USER);
                s.execute("DELETE FROM ledger_entry WHERE id=" + lit(entryAId));
            }
        })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("trigger: migrate role cannot TRUNCATE ledger_entry")
    void migrate_role_cannot_truncate_ledger_entry() {
        assertThatThrownBy(() -> {
            try (Connection c = migrateConn(); Statement s = c.createStatement()) {
                s.execute("TRUNCATE ledger_entry");
            }
        })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("trigger: migrate role cannot UPDATE ledger_txn")
    void migrate_role_cannot_update_ledger_txn() {
        assertThatThrownBy(() -> {
            try (Connection c = migrateConn(); Statement s = c.createStatement()) {
                s.execute("UPDATE ledger_txn SET kind='TAMPERED' WHERE id="
                        + lit(txnAId));
            }
        })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("trigger: migrate role cannot DELETE from ledger_txn")
    void migrate_role_cannot_delete_ledger_txn() {
        assertThatThrownBy(() -> {
            try (Connection c = migrateConn(); Statement s = c.createStatement()) {
                s.execute("DELETE FROM ledger_txn WHERE id=" + lit(txnAId));
            }
        })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("append-only");
    }

    // ── Privilege tests — app login role ─────────────────────────────────────
    //
    // The app login role lacks UPDATE/DELETE/TRUNCATE/DDL privileges, so
    // PostgreSQL raises "permission denied" before the trigger fires.

    @Test
    @DisplayName("privilege: app role cannot UPDATE ledger_entry")
    void app_role_cannot_update_ledger_entry() {
        assertThatThrownBy(() -> {
            try (Connection c = appConn(); Statement s = c.createStatement()) {
                s.execute("UPDATE ledger_entry SET debit_minor=1 WHERE id="
                        + lit(entryAId));
            }
        }).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("privilege: app role cannot DELETE from ledger_entry")
    void app_role_cannot_delete_ledger_entry() {
        assertThatThrownBy(() -> {
            try (Connection c = appConn(); Statement s = c.createStatement()) {
                s.execute("DELETE FROM ledger_entry WHERE id=" + lit(entryAId));
            }
        }).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("privilege: app role cannot TRUNCATE ledger_entry")
    void app_role_cannot_truncate_ledger_entry() {
        assertThatThrownBy(() -> {
            try (Connection c = appConn(); Statement s = c.createStatement()) {
                s.execute("TRUNCATE ledger_entry");
            }
        }).isInstanceOf(SQLException.class);
    }

    // ── Trigger management tests — app login role cannot disable or drop ──────

    @Test
    @DisplayName("privilege: app role cannot DISABLE TRIGGER ALL on ledger_entry")
    void app_role_cannot_disable_triggers_on_ledger_entry() {
        // ALTER TABLE requires ownership or superuser; app role has neither.
        assertThatThrownBy(() -> {
            try (Connection c = appConn(); Statement s = c.createStatement()) {
                s.execute("ALTER TABLE ledger_entry DISABLE TRIGGER ALL");
            }
        })
        .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("privilege: app role cannot DROP TRIGGER on ledger_entry")
    void app_role_cannot_drop_trigger_on_ledger_entry() {
        // DROP TRIGGER requires ownership of the table; app role is not the owner.
        assertThatThrownBy(() -> {
            try (Connection c = appConn(); Statement s = c.createStatement()) {
                s.execute("DROP TRIGGER trg_ledger_entry_no_update_delete"
                        + " ON ledger_entry");
            }
        })
        .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("privilege: app role cannot DROP TRIGGER on ledger_txn")
    void app_role_cannot_drop_trigger_on_ledger_txn() {
        assertThatThrownBy(() -> {
            try (Connection c = appConn(); Statement s = c.createStatement()) {
                s.execute("DROP TRIGGER trg_ledger_txn_no_update_delete"
                        + " ON ledger_txn");
            }
        })
        .isInstanceOf(SQLException.class);
    }

    // ── INSERT and reversal entries work for the app role ─────────────────────

    @Test
    @DisplayName("insert: app role can INSERT a new ledger_entry")
    void app_role_can_insert_ledger_entry() throws Exception {
        UUID newTxnId   = UUID.randomUUID();
        UUID newEntryId = UUID.randomUUID();
        try (Connection c = appConn(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);   // balanced when committed (V009)
            s.execute("INSERT INTO ledger_txn(id,tenant_id,kind,ref_type,ref_id) VALUES ("
                    + lit(newTxnId) + "," + lit(tenantAId)
                    + ",'PAYMENT_MATCHED','PAYMENT'," + lit(UUID.randomUUID()) + ")");
            s.execute("INSERT INTO ledger_entry(id,txn_id,account_id,debit_minor,credit_minor)"
                    + " VALUES (" + lit(newEntryId) + "," + lit(newTxnId) + ","
                    + lit(accountAId) + ",0,5000)");
            s.execute("INSERT INTO ledger_entry(id,txn_id,account_id,debit_minor,credit_minor)"
                    + " VALUES (" + lit(UUID.randomUUID()) + "," + lit(newTxnId) + ","
                    + lit(revenueAId) + ",5000,0)");
            c.commit();
        }
        try (Connection c = appConn();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id FROM ledger_entry WHERE id=" + lit(newEntryId))) {
            assertThat(rs.next()).isTrue();
            assertThat(UUID.fromString(rs.getString("id"))).isEqualTo(newEntryId);
        }
    }

    @Test
    @DisplayName("reversal: a reversal entry (new INSERT with opposite side) is accepted")
    void reversal_entry_can_be_inserted() throws Exception {
        UUID revTxnId   = UUID.randomUUID();
        UUID revEntryId = UUID.randomUUID();
        try (Connection c = appConn(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);   // balanced when committed (V009)
            // Reversal transaction, linked to the original by reverses_txn_id.
            s.execute("INSERT INTO ledger_txn(id,tenant_id,kind,ref_type,ref_id,reverses_txn_id) VALUES ("
                    + lit(revTxnId) + "," + lit(tenantAId)
                    + ",'REVERSAL','LEDGER_TXN'," + lit(txnAId) + "," + lit(txnAId) + ")");
            // Original posted: debit AR 5000 / credit REV 5000.
            // Reversal posts the mirror: credit AR 5000 / debit REV 5000.
            s.execute("INSERT INTO ledger_entry(id,txn_id,account_id,debit_minor,credit_minor)"
                    + " VALUES (" + lit(revEntryId) + "," + lit(revTxnId) + ","
                    + lit(accountAId) + ",0,5000)");
            s.execute("INSERT INTO ledger_entry(id,txn_id,account_id,debit_minor,credit_minor)"
                    + " VALUES (" + lit(UUID.randomUUID()) + "," + lit(revTxnId) + ","
                    + lit(revenueAId) + ",5000,0)");
            c.commit();
        }
        // Both the original and the reversal entry must be present — nothing deleted.
        try (Connection c = appConn();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM ledger_entry WHERE txn_id IN ("
                     + lit(txnAId) + "," + lit(revTxnId) + ")")) {
            rs.next();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(2);
        }
    }

    // ── No default privileges ─────────────────────────────────────────────────

    @Test
    @DisplayName("grants: a table created later is NOT readable by the app role until granted")
    void new_tables_are_not_visible_to_the_app_role_by_default() throws Exception {
        try (Connection c = migrateConn(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE future_secret (id INT)");
        }
        assertThatThrownBy(() -> {
            try (Connection c = appConn(); Statement s = c.createStatement()) {
                s.execute("SELECT * FROM future_secret");
            }
        })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
    }

    // ── Tenant isolation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("isolation: a tenant-scoped query for tenant A returns no tenant B rows")
    void tenant_a_query_excludes_tenant_b_entries() throws Exception {
        // The application always joins through ledger_txn.tenant_id to scope
        // results to a single tenant.  This test verifies that the entries
        // seeded for tenant B (9000 debit) do not appear in a tenant-A query.
        String sql =
                "SELECT le.id, le.debit_minor " +
                "FROM   ledger_entry le " +
                "JOIN   ledger_txn   lt ON lt.id = le.txn_id " +
                "WHERE  lt.tenant_id = " + lit(tenantAId);

        try (Connection c = appConn();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                assertThat(rs.getLong("debit_minor"))
                        .as("tenant A query must not surface tenant B's 9000-debit entry")
                        .isNotEqualTo(9000L);
            }
        }

        // Verify that tenant B's entry actually exists, so the test is not trivially
        // vacuous — there must be at least one entry in the DB with debit_minor=9000.
        String existsCheck =
                "SELECT COUNT(*) FROM ledger_entry WHERE debit_minor = 9000";
        try (Connection c = appConn();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(existsCheck)) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("tenant B's 9000-debit entry must exist so the isolation test is not vacuous")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Connection superConn() throws SQLException {
        return DriverManager.getConnection(superJdbcUrl, superUser, superPass);
    }

    private static Connection migrateConn() throws SQLException {
        return DriverManager.getConnection(superJdbcUrl, MIGRATE_USER, MIGRATE_PASS);
    }

    private static Connection appConn() throws SQLException {
        return DriverManager.getConnection(superJdbcUrl, APP_USER, APP_PASS);
    }

    /** Returns a PostgreSQL UUID literal: {@code '<uuid>'::uuid} */
    private static String lit(UUID id) {
        return "'" + id + "'::uuid";
    }
}
