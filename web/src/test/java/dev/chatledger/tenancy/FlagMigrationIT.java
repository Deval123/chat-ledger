package dev.chatledger.tenancy;

import dev.chatledger.ledger.LedgerDatabase;
import dev.chatledger.suspicion.FlagCode;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.chatledger.tenancy.TenantSeed.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V019 on a database that already holds payments (written at V018): the {@code DUPLICATE} and {@code UNVERIFIED} payments get their flag,
 * with the code's own sentence, and nothing else does. And V019 refuses to run over a flag table that holds rows, rather than guessing how to
 * type an old subject.
 *
 * <p>Task 7, D9.
 */
class FlagMigrationIT {

    private static Connection superuser(PostgreSQLContainer<?> pg) throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private static UUID seedTenant(Connection c) throws SQLException {
        UUID tenant = UUID.randomUUID();
        update(c, "INSERT INTO tenant(id, name, currency, scale, slug) VALUES (?, 'Legacy seller', 'XAF', 0, ?)", tenant, "legacy-" + tenant.toString().substring(0, 8));
        return tenant;
    }

    private static UUID payment(Connection c, UUID tenant, String status, String tx, UUID duplicateOf) throws SQLException {
        UUID id = UUID.randomUUID();
        update(c, "INSERT INTO payment(id, tenant_id, external_id, provider, tx_id, amount_minor, currency, status, raw_text, paid_at, duplicate_of)"
                + " VALUES (?, ?, ?, 'PROVIDER_A', ?, 100, 'XAF', ?, 'synthetic', now(), ?)", id, tenant, "ext-" + id, tx, status, duplicateOf);
        return id;
    }

    @Test
    void the_payments_that_are_already_repeats_or_unverified_get_their_flag_and_no_other_does() throws SQLException {
        try (PostgreSQLContainer<?> pg = LedgerDatabase.newContainer()) {
            pg.start();
            LedgerDatabase.bootstrapRoles(pg);
            LedgerDatabase.flywayFor(pg, "18").migrate();
            UUID tenant;
            UUID received;
            UUID duplicate;
            UUID unverified;
            try (Connection c = superuser(pg)) {
                c.setAutoCommit(false);
                tenant = seedTenant(c);
                received = payment(c, tenant, "RECEIVED", "TXLEGACY0001", null);
                duplicate = payment(c, tenant, "DUPLICATE", "TXLEGACY0001", received);
                unverified = payment(c, tenant, "UNVERIFIED", null, null);
                c.commit();
            }
            LedgerDatabase.flywayFor(pg, null).migrate();
            List<String> flags = new ArrayList<>();
            try (Connection c = superuser(pg); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT payment_id, code, reason, subject_type, order_id IS NULL, resolved_at IS NULL FROM flag ORDER BY code")) {
                while (rs.next()) {
                    flags.add(rs.getObject(1, UUID.class) + " " + rs.getString(2) + " " + rs.getString(3) + " " + rs.getString(4) + " " + rs.getBoolean(5) + " " + rs.getBoolean(6));
                }
            }
            assertThat(flags).containsExactly(
                    duplicate + " DUPLICATE_TX " + FlagCode.DUPLICATE_TX.sentence() + " PAYMENT true true",
                    unverified + " NO_PROVIDER_ID " + FlagCode.NO_PROVIDER_ID.sentence() + " PAYMENT true true");
            assertThat(flags).noneMatch(line -> line.startsWith(received.toString()));
        }
    }

    @Test
    void the_migration_refuses_a_flag_table_that_already_holds_rows() throws SQLException {
        try (PostgreSQLContainer<?> pg = LedgerDatabase.newContainer()) {
            pg.start();
            LedgerDatabase.bootstrapRoles(pg);
            LedgerDatabase.flywayFor(pg, "18").migrate();
            try (Connection c = superuser(pg)) {
                c.setAutoCommit(false);
                UUID tenant = seedTenant(c);
                UUID p = payment(c, tenant, "RECEIVED", "TXLEGACY0002", null);
                update(c, "INSERT INTO flag(id, tenant_id, subject_type, subject_id, code, reason) VALUES (?, ?, 'PAYMENT', ?, 'SOMETHING', 'old')", UUID.randomUUID(), tenant, p);
                c.commit();
            }
            assertThatThrownBy(() -> LedgerDatabase.flywayFor(pg, null).migrate()).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("V019 expects the flag table to be empty");
            try (Connection c = superuser(pg); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM flag")) {
                rs.next();
                assertThat(rs.getLong(1)).as("nothing was dropped or rewritten").isEqualTo(1);
            }
        }
    }
}
