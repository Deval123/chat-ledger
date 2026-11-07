package dev.chatledger.tenancy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves, before any production schema depends on it, the assumption behind
 * the row-level-security design: a transaction-local tenant setting
 * ({@code set_config('app.tenant_id', ..., true)}) is still readable, and
 * row-level security still applies, when a DEFERRED constraint trigger fires at
 * COMMIT.
 *
 * <p>This is a minimal scratch schema on purpose (a table with a tenant policy and
 * a deferred trigger that records what it sees), so the result does not depend on
 * the ledger migrations. The same property is re-checked against the real ledger
 * tables in the row-level-security tests.
 *
 * <p>The control test is what makes the first one meaningful: if the trigger ran
 * with the table owner's rights, or ignored RLS, it would see all rows even with
 * the setting blanked, and the control would fail.
 *
 * <p>Design: "Tenancy and authentication", Decision 2. Task 3.2.
 */
@Testcontainers
class CommitTimeTenantSettingIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine")
                    .withDatabaseName("scratch")
                    .withUsername("scratch_owner")
                    .withPassword(UUID.randomUUID().toString());

    private static final String PROBE_USER = "probe_app";
    private static final String PROBE_PASSWORD = UUID.randomUUID().toString();

    @BeforeAll
    static void scratchSchema() throws SQLException {
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            // A non-owner, non-superuser role, like the application role.
            s.execute("CREATE ROLE " + PROBE_USER + " LOGIN PASSWORD '" + PROBE_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE");
            s.execute("CREATE TABLE scratch_entry (id UUID PRIMARY KEY DEFAULT gen_random_uuid(),"
                    + " tenant_id UUID NOT NULL, txn UUID NOT NULL)");
            s.execute("CREATE TABLE scratch_observation (seen_setting TEXT, visible_rows BIGINT)");
            s.execute("ALTER TABLE scratch_entry ENABLE ROW LEVEL SECURITY");
            s.execute("CREATE FUNCTION scratch_tenant() RETURNS UUID LANGUAGE sql STABLE AS "
                    + "$$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$");
            s.execute("CREATE POLICY isolation ON scratch_entry TO " + PROBE_USER
                    + " USING (tenant_id = scratch_tenant()) WITH CHECK (tenant_id = scratch_tenant())");
            // Records what a deferred trigger sees at commit.
            s.execute("CREATE FUNCTION scratch_observe() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN "
                    + "INSERT INTO scratch_observation "
                    + "SELECT current_setting('app.tenant_id', true), "
                    + "(SELECT count(*) FROM scratch_entry WHERE txn = NEW.txn); "
                    + "RETURN NULL; END $$");
            s.execute("CREATE CONSTRAINT TRIGGER scratch_deferred AFTER INSERT ON scratch_entry "
                    + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION scratch_observe()");
            s.execute("GRANT SELECT, INSERT ON scratch_entry, scratch_observation TO " + PROBE_USER);
        }
    }

    @Test
    void deferred_trigger_reads_the_tenant_setting_and_sees_its_own_rows_at_commit() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        try (Connection c = probe()) {
            c.setAutoCommit(false);
            setTenant(c, tenant.toString());
            insert(c, tenant, txn);
            insert(c, tenant, txn);
            c.commit();   // the deferred trigger fires here, once per inserted row
        }

        List<Observation> seen = observations(txn);
        assertThat(seen).as("the deferred trigger fired for each inserted row").hasSize(2);
        assertThat(seen).allSatisfy(o -> {
            assertThat(o.setting()).as("setting readable at commit").isEqualTo(tenant.toString());
            assertThat(o.visibleRows()).as("rows visible to the trigger through RLS").isEqualTo(2);
        });
    }

    @Test
    void control_with_the_setting_blanked_before_commit_the_trigger_sees_nothing() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID txn = UUID.randomUUID();

        try (Connection c = probe()) {
            c.setAutoCommit(false);
            setTenant(c, tenant.toString());
            insert(c, tenant, txn);
            insert(c, tenant, txn);
            setTenant(c, "");     // as if the setting were missing when the trigger fires
            c.commit();
        }

        List<Observation> seen = observations(txn);
        assertThat(seen).hasSize(2);
        assertThat(seen).allSatisfy(o -> {
            assertThat(o.setting()).isEmpty();
            assertThat(o.visibleRows())
                    .as("RLS applies inside the deferred trigger: with no tenant it sees no rows")
                    .isZero();
        });
    }

    @Test
    void control_the_setting_does_not_leak_to_the_next_transaction_on_the_same_connection() throws SQLException {
        UUID tenant = UUID.randomUUID();

        try (Connection c = probe()) {
            c.setAutoCommit(false);
            setTenant(c, tenant.toString());
            c.commit();

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT current_setting('app.tenant_id', true)")) {
                rs.next();
                String after = rs.getString(1);
                assertThat(after == null || after.isEmpty())
                        .as("a pooled connection must not carry the tenant to its next borrower, got: %s", after)
                        .isTrue();
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private record Observation(String setting, long visibleRows) {}

    private static Connection probe() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), PROBE_USER, PROBE_PASSWORD);
    }

    private static void setTenant(Connection c, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, value);
            ps.execute();
        }
    }

    private static void insert(Connection c, UUID tenant, UUID txn) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO scratch_entry(tenant_id, txn) VALUES (?, ?)")) {
            ps.setObject(1, tenant);
            ps.setObject(2, txn);
            ps.executeUpdate();
        }
    }

    /** Read as the owner (not subject to RLS) what the deferred trigger recorded for one transaction. */
    private static List<Observation> observations(UUID txn) throws SQLException {
        // The observation table has no txn column, so match on the rows the trigger counted:
        // each test uses a fresh table state per txn by reading and then clearing.
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            List<Observation> result = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SELECT seen_setting, visible_rows FROM scratch_observation")) {
                while (rs.next()) {
                    result.add(new Observation(rs.getString(1), rs.getLong(2)));
                }
            }
            s.execute("TRUNCATE scratch_observation");
            return result;
        }
    }
}
