package dev.chatledger.security;

import dev.chatledger.ledger.LedgerDatabase;
import dev.chatledger.tenancy.TestHashes;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant A cannot read tenant B through the authentication repositories, even when A asks for
 * B's exact email, user id or audit rows. Both the query and row-level security stand in the way;
 * {@code TenantIsolationCoverageIT} proves the policies, this proves the code on top of them.
 *
 * <p>Registered in {@code IsolationRegistry.REPOSITORY_CONTRACT_TESTS}.
 *
 * <p>Requirement: 9.1. Task 3.5.
 */
class RepositoryIsolationIT {

    private static final LedgerDatabase DB = LedgerDatabase.get();

    private final AppUserRepository users = new AppUserRepository(DB.appDataSource());
    private final AuditLogRepository audit = new AuditLogRepository(DB.appDataSource());

    private UUID insertUser(UUID tenant, String email) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'SELLER')")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setString(3, email);
            ps.setString(4, TestHashes.UNUSABLE);
            ps.executeUpdate();
        }
        return id;
    }

    @Test
    void a_user_of_another_tenant_cannot_be_read() throws SQLException {
        UUID a = DB.createTenant();
        UUID b = DB.createTenant();
        String emailB = "b-" + UUID.randomUUID() + "@example.test";
        UUID userB = insertUser(b, emailB);
        String emailA = "a-" + UUID.randomUUID() + "@example.test";
        UUID userA = insertUser(a, emailA);

        // Positive control: each tenant reads its own user, so the empty results below mean something.
        assertThat(users.findByEmail(b, emailB)).isPresent().get().extracting(AppUser::id).isEqualTo(userB);
        assertThat(users.findById(a, userA)).isPresent();

        assertThat(users.findByEmail(a, emailB)).isEmpty();
        assertThat(users.findById(a, userB)).isEmpty();
        assertThat(users.findByEmail(b, emailA)).isEmpty();
        assertThat(users.findById(b, userA)).isEmpty();
    }

    @Test
    void the_same_email_in_two_tenants_resolves_to_each_tenants_own_user() throws SQLException {
        UUID a = DB.createTenant();
        UUID b = DB.createTenant();
        String email = "same-" + UUID.randomUUID() + "@example.test";
        UUID userA = insertUser(a, email);
        UUID userB = insertUser(b, email);
        assertThat(users.findByEmail(a, email)).get().extracting(AppUser::id).isEqualTo(userA);
        assertThat(users.findByEmail(b, email)).get().extracting(AppUser::id).isEqualTo(userB);
    }

    @Test
    void the_audit_trail_of_another_tenant_cannot_be_read() {
        UUID a = DB.createTenant();
        UUID b = DB.createTenant();
        audit.append(b, null, "LOGIN_SUCCESS", "tenant-b-marker");
        audit.append(a, null, "LOGIN_SUCCESS", "tenant-a-marker");

        assertThat(audit.recent(b, 50)).extracting(AuditLogRepository.Entry::subject).containsExactly("tenant-b-marker");
        assertThat(audit.recent(a, 50)).extracting(AuditLogRepository.Entry::subject).containsExactly("tenant-a-marker");
    }

    @Test
    void an_entry_cannot_be_written_for_another_tenant_through_the_scope_of_this_one() {
        UUID a = DB.createTenant();
        UUID b = DB.createTenant();
        // The scope is set to tenant a, but the row names tenant b: row-level security refuses the write.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            try (Connection c = DB.appDataSource().getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement set = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)");
                     PreparedStatement insert = c.prepareStatement(
                             "INSERT INTO audit_log(id, tenant_id, action, subject) VALUES (?, ?, 'X', 'x')")) {
                    set.setString(1, a.toString());
                    set.execute();
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, b);
                    insert.executeUpdate();
                }
            }
        }).isInstanceOf(SQLException.class);
        assertThat(audit.recent(b, 50)).isEmpty();
    }
}
