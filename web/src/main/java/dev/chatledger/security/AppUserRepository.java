package dev.chatledger.security;

import dev.chatledger.tenancy.TenantScope;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads users. Every read runs in a {@link TenantScope}, so row-level security applies, and
 * every query names the tenant as well. The application role can only read here: it cannot create
 * users or change a role (it holds no INSERT or UPDATE on app_user).
 *
 * <p>Each statement is a complete string literal, which the build checks (no SQL is built by
 * concatenation).
 */
@Repository
public class AppUserRepository {

    private final TenantScope scope;

    public AppUserRepository(DataSource dataSource) {
        this.scope = new TenantScope(dataSource);
    }

    public Optional<AppUser> findByEmail(UUID tenantId, String email) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT u.id, u.tenant_id, u.email, u.password_hash, u.role, u.disabled_at, t.disabled_at "
                                + "FROM app_user u JOIN tenant t ON t.id = u.tenant_id "
                                + "WHERE u.tenant_id = ? AND u.email = ?")) {
                    ps.setObject(1, tenantId);
                    ps.setString(2, email);
                    return readOne(ps);
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read a user", e);
        }
    }

    public Optional<AppUser> findById(UUID tenantId, UUID userId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT u.id, u.tenant_id, u.email, u.password_hash, u.role, u.disabled_at, t.disabled_at "
                                + "FROM app_user u JOIN tenant t ON t.id = u.tenant_id "
                                + "WHERE u.tenant_id = ? AND u.id = ?")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, userId);
                    return readOne(ps);
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read a user", e);
        }
    }

    private static Optional<AppUser> readOne(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new AppUser(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                    Role.valueOf(rs.getString(5)), instant(rs.getTimestamp(6)), instant(rs.getTimestamp(7))));
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
