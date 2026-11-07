package dev.chatledger.tenancy;

import dev.chatledger.ledger.LedgerDatabase;
import dev.chatledger.tenancy.IsolationRegistry.TableSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The isolation coverage guard, run on the real database.
 *
 * <ol>
 *   <li>The real catalog satisfies every rule of {@link IsolationGuard}: every table is
 *       registered, has row-level security and a tenant policy, every foreign key between
 *       tenant tables includes {@code tenant_id}, and no unlisted SECURITY DEFINER
 *       function exists. A table added later fails this until it is registered.</li>
 *   <li>For every registered table, behaviour is checked as a role that holds full
 *       DML privileges and is subject to the policies, so a refusal can only come from
 *       row-level security and not from missing privileges.</li>
 *   <li>The application login is not an owner, superuser or BYPASSRLS, and holds none
 *       of the privileges that row-level security does not cover.</li>
 * </ol>
 *
 * <p>Design: "Tenancy and authentication", decision 2. Task 3.3.
 */
class TenantIsolationCoverageIT {

    private static final LedgerDatabase DB = LedgerDatabase.get();
    private static final String PROBE = "rls_probe";

    private static TenantSeed seedA;
    private static TenantSeed seedB;
    private static DataSource probe;
    private static TenantScope probeScope;

    @BeforeAll
    static void setUp() throws SQLException {
        seedA = TenantSeed.create(DB.migrateDataSource());
        seedB = TenantSeed.create(DB.migrateDataSource());

        // A role subject to the policies (it is a member of chatledger_app) that also holds every DML
        // privilege. With it, a refused write can only have been refused by row-level security.
        String password = UUID.randomUUID().toString();
        try (Connection c = DB.superDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + PROBE + "') THEN "
                    + "CREATE ROLE " + PROBE + " LOGIN PASSWORD '" + password
                    + "' IN ROLE chatledger_app NOSUPERUSER NOBYPASSRLS; END IF; END $$");
            for (String table : IsolationRegistry.TABLES.keySet()) {
                s.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON " + table + " TO " + PROBE);
            }
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(DB.jdbcUrl());
        ds.setUser(PROBE);
        ds.setPassword(password);
        probe = ds;
        probeScope = new TenantScope(probe);
    }

    // ── 1. The catalog ──────────────────────────────────────────────────────

    @Test
    void the_real_catalog_satisfies_every_isolation_rule() throws SQLException {
        try (Connection c = DB.migrateDataSource().getConnection()) {
            IsolationGuard.Catalog catalog = IsolationGuard.load(c);

            assertThat(catalog.tables().stream().map(IsolationGuard.TableInfo::name))
                    .as("the catalog was actually read").contains("orders", "ledger_entry", "flyway_schema_history");
            assertThat(catalog.foreignKeys()).as("foreign keys were read").isNotEmpty();

            assertThat(IsolationGuard.violations(catalog, IsolationRegistry.TABLES,
                    IsolationRegistry.NON_TENANT_TABLES.keySet(), IsolationRegistry.FOREIGN_KEY_EXCEPTIONS,
                    IsolationRegistry.ALLOWED_DEFINER_FUNCTIONS, IsolationRegistry.APP_ROLE)).isEmpty();
        }
    }

    // ── 2. The application roles ────────────────────────────────────────────

    @Test
    void the_application_roles_are_neither_owner_nor_superuser_nor_bypassrls() throws SQLException {
        try (Connection c = DB.migrateDataSource().getConnection(); Statement s = c.createStatement()) {
            int seen = 0;
            try (ResultSet rs = s.executeQuery("SELECT rolname, rolsuper, rolbypassrls, rolcreaterole, rolcreatedb "
                    + "FROM pg_roles WHERE rolname IN ('chatledger_app', '" + LedgerDatabase.APP_USER + "')")) {
                while (rs.next()) {
                    seen++;
                    String name = rs.getString(1);
                    assertThat(rs.getBoolean(2)).as(name + " superuser").isFalse();
                    assertThat(rs.getBoolean(3)).as(name + " BYPASSRLS").isFalse();
                    assertThat(rs.getBoolean(4)).as(name + " CREATEROLE").isFalse();
                    assertThat(rs.getBoolean(5)).as(name + " CREATEDB").isFalse();
                }
            }
            assertThat(seen).isEqualTo(2);

            try (ResultSet rs = s.executeQuery("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = 'public' AND pg_get_userbyid(c.relowner) IN ('chatledger_app', '"
                    + LedgerDatabase.APP_USER + "')")) {
                assertThat(rs.next()).as("the application role owns no table, view or sequence").isFalse();
            }
            try (ResultSet rs = s.executeQuery("SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                    + "WHERE n.nspname = 'public' AND pg_get_userbyid(p.proowner) IN ('chatledger_app', '"
                    + LedgerDatabase.APP_USER + "')")) {
                assertThat(rs.next()).as("the application role owns no function").isFalse();
            }
            try (ResultSet rs = s.executeQuery("SELECT has_schema_privilege('" + LedgerDatabase.APP_USER + "', 'public', 'CREATE')")) {
                rs.next();
                assertThat(rs.getBoolean(1)).as("the application role cannot create objects (no DDL)").isFalse();
            }
        }
    }

    @Test
    void the_application_login_holds_no_privilege_that_row_level_security_does_not_cover() throws SQLException {
        try (Connection c = DB.migrateDataSource().getConnection(); Statement s = c.createStatement()) {
            List<String> tables = new java.util.ArrayList<>(IsolationRegistry.TABLES.keySet());
            tables.addAll(IsolationRegistry.NON_TENANT_TABLES.keySet());
            for (String table : tables) {
                for (String privilege : List.of("TRUNCATE", "REFERENCES", "TRIGGER")) {
                    try (ResultSet rs = s.executeQuery("SELECT has_table_privilege('" + LedgerDatabase.APP_USER
                            + "', 'public." + table + "', '" + privilege + "')")) {
                        rs.next();
                        assertThat(rs.getBoolean(1)).as("%s on %s", privilege, table).isFalse();
                    }
                }
            }
            try (ResultSet rs = s.executeQuery("SELECT has_table_privilege('" + LedgerDatabase.APP_USER
                    + "', 'public.flyway_schema_history', 'SELECT')")) {
                rs.next();
                assertThat(rs.getBoolean(1)).as("the application role cannot read Flyway's history").isFalse();
            }
            for (String table : List.of("ledger_entry", "ledger_txn", "audit_log")) {
                for (String privilege : List.of("UPDATE", "DELETE")) {
                    try (ResultSet rs = s.executeQuery("SELECT has_table_privilege('" + LedgerDatabase.APP_USER
                            + "', 'public." + table + "', '" + privilege + "')")) {
                        rs.next();
                        assertThat(rs.getBoolean(1)).as("%s on append-only %s", privilege, table).isFalse();
                    }
                }
            }
        }
    }

    // ── 3. Behaviour, per registered table ──────────────────────────────────

    static Stream<String> registeredTables() {
        return IsolationRegistry.TABLES.keySet().stream().sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("registeredTables")
    void rows_are_isolated_per_tenant(String table) throws SQLException {
        TableSpec spec = IsolationRegistry.TABLES.get(table);
        Set<UUID> a = new HashSet<>(seedA.idsByTable().get(table));
        Set<UUID> b = new HashSet<>(seedB.idsByTable().get(table));
        assertThat(a).as("the seed has rows for " + table).isNotEmpty();

        // Reads: each tenant sees exactly its own rows.
        assertThat(visibleIds(seedA.tenantId(), table)).containsExactlyInAnyOrderElementsOf(a);
        assertThat(visibleIds(seedB.tenantId(), table)).containsExactlyInAnyOrderElementsOf(b);

        // No tenant set: nothing is visible.
        try (Connection c = probe.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            assertThat(rs.getLong(1)).as("rows visible with no tenant set").isZero();
        }

        // Another tenant's rows cannot be changed or removed (zero rows are touched).
        assertThat(affected(seedA.tenantId(), "UPDATE " + table + " SET id = id WHERE id = ANY (?)", b)).isZero();
        assertThat(affected(seedA.tenantId(), "DELETE FROM " + table + " WHERE id = ANY (?)", b)).isZero();
        assertThat(ownerCount(table, b)).as("tenant B's rows are untouched").isEqualTo(b.size());

        // Writes. The probe holds INSERT privilege, so a refusal is the policy (or, for ledger_entry, the
        // fail-closed trigger). Row B is internally consistent (its parents are B's), so nothing else can refuse it.
        if (!spec.selectOnly()) {
            rolledBack(seedA.tenantId(), c -> spec.insert().insert(c, seedA.tenantId(), seedA));   // control: own tenant is accepted
        }
        assertThatThrownBy(() -> rolledBack(seedA.tenantId(), c -> spec.insert().insert(c, seedB.tenantId(), seedB)))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState())
                        .as("a row for another tenant is refused by row-level security: %s", e.getMessage())
                        .isIn("42501", "23514"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static Set<UUID> visibleIds(UUID tenant, String table) throws SQLException {
        return probeScope.call(tenant, c -> {
            Set<UUID> ids = new HashSet<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id FROM " + table)) {
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                }
            }
            return ids;
        });
    }

    private static int affected(UUID tenant, String sql, Set<UUID> ids) throws SQLException {
        return probeScope.call(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Array array = c.createArrayOf("uuid", ids.toArray());
                ps.setArray(1, array);
                return ps.executeUpdate();
            }
        });
    }

    private static long ownerCount(String table, Set<UUID> ids) throws SQLException {
        try (Connection c = DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE id = ANY (?)")) {
            ps.setArray(1, c.createArrayOf("uuid", ids.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @FunctionalInterface
    private interface Work {
        void run(Connection c) throws SQLException;
    }

    /** Runs the work as the probe in a transaction for the tenant, and always rolls back. */
    private static void rolledBack(UUID tenant, Work work) throws SQLException {
        try (Connection c = probe.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, tenant.toString());
                ps.execute();
            }
            try {
                work.run(c);
            } finally {
                c.rollback();
            }
        }
    }
}
