package dev.chatledger.tenancy;

import dev.chatledger.ledger.LedgerDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static dev.chatledger.tenancy.TenantSeed.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V012: audit_log is append-only, enforced by the database. The triggers refuse UPDATE,
 * DELETE and TRUNCATE even for the table owner, who is not stopped by privileges.
 * Appending still works, which is the point of an audit trail.
 *
 * <p>The limit, named: the owner or a superuser can drop the triggers. The application
 * role cannot (it is not the owner), and it also lacks the UPDATE and DELETE privileges.
 */
class AuditLogImmutabilityIT {

    private static final LedgerDatabase DB = LedgerDatabase.get();
    private static TenantSeed seed;

    @BeforeAll
    static void seed() {
        seed = TenantSeed.create(DB.migrateDataSource());
    }

    @Test
    void the_owner_can_append_an_audit_entry() throws SQLException {
        try (Connection c = DB.migrateDataSource().getConnection()) {
            update(c, "INSERT INTO audit_log(id, tenant_id, actor, action, subject) VALUES (?, ?, ?, 'TEST', 'append')",
                    UUID.randomUUID(), seed.tenantId(), seed.userId());
        }
    }

    @Test
    void the_owner_cannot_update_an_audit_entry() {
        assertThatThrownBy(() -> ownerExecutes("UPDATE audit_log SET action = 'TAMPERED' WHERE id = '" + seed.auditId() + "'"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getMessage()).contains("audit_log is append-only"));
    }

    @Test
    void the_owner_cannot_delete_an_audit_entry() {
        assertThatThrownBy(() -> ownerExecutes("DELETE FROM audit_log WHERE id = '" + seed.auditId() + "'"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getMessage()).contains("audit_log is append-only"));
    }

    @Test
    void the_owner_cannot_truncate_the_audit_log() {
        assertThatThrownBy(() -> ownerExecutes("TRUNCATE audit_log"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getMessage()).contains("audit_log is append-only"));
    }

    @Test
    void the_application_role_can_neither_change_the_trail_nor_drop_its_triggers() {
        for (String sql : new String[]{
                "UPDATE audit_log SET action = 'TAMPERED'",
                "DELETE FROM audit_log",
                "TRUNCATE audit_log",
                "ALTER TABLE audit_log DISABLE TRIGGER ALL",
                "DROP TRIGGER trg_audit_log_no_update_delete ON audit_log"}) {
            assertThatThrownBy(() -> {
                try (Connection c = DB.appDataSource().getConnection(); Statement s = c.createStatement()) {
                    s.execute(sql);
                }
            }).as(sql).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void the_entry_is_still_there_after_every_attempt() throws SQLException {
        try (Connection c = DB.migrateDataSource().getConnection(); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT action FROM audit_log WHERE id = '" + seed.auditId() + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("TEST");
        }
    }

    private static void ownerExecutes(String sql) throws SQLException {
        try (Connection c = DB.migrateDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
