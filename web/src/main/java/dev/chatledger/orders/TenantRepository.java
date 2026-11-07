package dev.chatledger.orders;

import dev.chatledger.tenancy.TenantScope;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** Reads the tenant's own row (currency and scale). Row-level security lets a tenant see only its own row. */
@Repository
public class TenantRepository {

    private final TenantScope scope;

    public TenantRepository(DataSource dataSource) {
        this.scope = new TenantScope(dataSource);
    }

    public Optional<TenantMoney> money(UUID tenantId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT currency, scale FROM tenant WHERE id = ? AND disabled_at IS NULL")) {
                    ps.setObject(1, tenantId);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(new TenantMoney(rs.getString(1).strip(), rs.getInt(2))) : Optional.<TenantMoney>empty();
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the tenant", e);
        }
    }
}
