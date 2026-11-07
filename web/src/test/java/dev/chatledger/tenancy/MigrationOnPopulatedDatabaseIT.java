package dev.chatledger.tenancy;

import dev.chatledger.ledger.LedgerDatabase;
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

import static dev.chatledger.tenancy.TenantSeed.update;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V010 and V011 must apply to a database that already holds rows, not only to an
 * empty one. Legacy rows are written at V009, then the migrations run.
 *
 * <p>Task 3.1.
 */
@Testcontainers
class MigrationOnPopulatedDatabaseIT {

    @Container
    static final PostgreSQLContainer<?> PG = LedgerDatabase.newContainer();

    @Test
    void existing_rows_are_back_filled_and_kept() throws SQLException {
        LedgerDatabase.bootstrapRoles(PG);
        LedgerDatabase.flywayFor(PG, "9").migrate();

        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        UUID match = UUID.randomUUID();
        try (Connection c = superuser()) {
            c.setAutoCommit(false);
            // Legacy shapes: no slug column yet, a mixed-case email, no tenant_id on order_line or match.
            update(c, "INSERT INTO tenant(id, name, currency, scale) VALUES (?, 'Legacy seller', 'XAF', 0)", tenant);
            update(c, "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'SELLER')",
                    user, tenant, "Legacy.Seller@Example.TEST", "{placeholder}not-a-hash");
            update(c, "INSERT INTO customer(id, tenant_id, display_name) VALUES (?, ?, 'Legacy customer')", customer, tenant);
            update(c, "INSERT INTO orders(id, tenant_id, external_id, customer_id, total_minor, currency, status, raw_text)"
                    + " VALUES (?, ?, 'legacy-1', ?, 100, 'XAF', 'PENDING_PAYMENT', 'x')", order, tenant, customer);
            update(c, "INSERT INTO order_line(id, order_id, label, qty, unit_minor) VALUES (?, ?, 'item', 1, 100)", line, order);
            update(c, "INSERT INTO payment(id, tenant_id, provider, tx_id, amount_minor, currency, status, raw_text)"
                    + " VALUES (?, ?, 'ProviderA', 'legacy-tx', 100, 'XAF', 'RECEIVED', 'x')", payment, tenant);
            update(c, "INSERT INTO match(id, order_id, payment_id, amount_minor, score, state) VALUES (?, ?, ?, 100, 90, 'AUTO')",
                    match, order, payment);
            c.commit();
        }

        LedgerDatabase.flywayFor(PG, null).migrate();

        try (Connection c = superuser(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT slug FROM tenant WHERE id = '" + tenant + "'")) {
                rs.next();
                assertThat(rs.getString(1)).matches("tenant-[0-9a-f]{8}");
            }
            try (ResultSet rs = s.executeQuery("SELECT email FROM app_user WHERE id = '" + user + "'")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("legacy.seller@example.test");
            }
            try (ResultSet rs = s.executeQuery("SELECT tenant_id FROM order_line WHERE id = '" + line + "'")) {
                rs.next();
                assertThat(rs.getObject(1, UUID.class)).isEqualTo(tenant);
            }
            try (ResultSet rs = s.executeQuery("SELECT tenant_id FROM match WHERE id = '" + match + "'")) {
                rs.next();
                assertThat(rs.getObject(1, UUID.class)).isEqualTo(tenant);
            }
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history' "
                    + "AND NOT c.relrowsecurity")) {
                rs.next();
                assertThat(rs.getLong(1)).as("tables still without row-level security").isZero();
            }
        }
    }

    private static Connection superuser() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }
}
