package dev.chatledger.orders;

import dev.chatledger.tenancy.TenantScope;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads customers. Customers are created inside the order transaction by {@link OrderRepository} (so an order that is
 * not stored leaves no customer behind). The phone itself is never stored, only its keyed hash and last four digits.
 */
@Repository
public class CustomerRepository {

    /** A customer as stored. {@code toString} leaves out the name and the hash. */
    public record Row(UUID id, String displayName, String phoneLast4, boolean hasPhoneHash) {
        @Override
        public String toString() {
            return "Customer[id=" + id + "]";
        }
    }

    private final TenantScope scope;

    public CustomerRepository(DataSource dataSource) {
        this.scope = new TenantScope(dataSource);
    }

    public Optional<Row> find(UUID tenantId, UUID customerId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, display_name, phone_last4, phone_hash IS NOT NULL FROM customer WHERE tenant_id = ? AND id = ?")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, customerId);
                    return one(ps);
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read a customer", e);
        }
    }

    public Optional<Row> findByPhoneHash(UUID tenantId, String phoneHash) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, display_name, phone_last4, phone_hash IS NOT NULL FROM customer WHERE tenant_id = ? AND phone_hash = ?")) {
                    ps.setObject(1, tenantId);
                    ps.setString(2, phoneHash);
                    return one(ps);
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read a customer", e);
        }
    }

    public long count(UUID tenantId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM customer WHERE tenant_id = ?")) {
                    ps.setObject(1, tenantId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read customers", e);
        }
    }

    private static Optional<Row> one(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            String last4 = rs.getString(3);
            return Optional.of(new Row(rs.getObject(1, UUID.class), rs.getString(2), last4 == null ? null : last4.strip(), rs.getBoolean(4)));
        }
    }
}
