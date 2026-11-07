package dev.chatledger.security;

import dev.chatledger.tenancy.TenantScope;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Appends to and reads the audit trail. The table is append-only (V012 triggers) and the application
 * role holds only INSERT and SELECT on it, so there is no method here that changes or removes an entry.
 */
@Repository
public class AuditLogRepository {

    /** One audit row, without the tenant (the caller already knows it). */
    public record Entry(UUID id, UUID actor, String action, String subject) {}

    private final TenantScope scope;

    public AuditLogRepository(DataSource dataSource) {
        this.scope = new TenantScope(dataSource);
    }

    /** Appends an entry. {@code actor} may be null (for an event with no signed-in user). */
    public void append(UUID tenantId, UUID actor, String action, String subject) {
        try {
            scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO audit_log(id, tenant_id, actor, action, subject) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, tenantId);
                    ps.setObject(3, actor);
                    ps.setString(4, action);
                    ps.setString(5, subject);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not write the audit trail", e);
        }
    }

    /** The newest entries of the tenant. */
    public List<Entry> recent(UUID tenantId, int limit) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, actor, action, subject FROM audit_log WHERE tenant_id = ? ORDER BY at DESC, id LIMIT ?")) {
                    ps.setObject(1, tenantId);
                    ps.setInt(2, limit);
                    List<Entry> entries = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            entries.add(new Entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                                    rs.getString(3), rs.getString(4)));
                        }
                    }
                    return entries;
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the audit trail", e);
        }
    }
}
