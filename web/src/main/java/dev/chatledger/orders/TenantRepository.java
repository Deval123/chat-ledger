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

    /**
     * What ingestion needs to know about the tenant before it reads a message: its money, whether it is a demo workspace (task 9), and
     * whether that demo workspace has reached its row cap ({@code demo_row_cap()}, the same number the database's trigger uses).
     */
    public record Intake(TenantMoney money, boolean demo, boolean full) {
    }

    private final TenantScope scope;

    public TenantRepository(DataSource dataSource) {
        this.scope = new TenantScope(dataSource);
    }

    public Optional<TenantMoney> money(UUID tenantId) {
        return intake(tenantId).map(Intake::money);
    }

    public Optional<Intake> intake(UUID tenantId) {
        try {
            return scope.call(tenantId, c -> {
                // CASE, not AND: a regular workspace never pays for the two counts.
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT t.currency, t.scale, t.kind = 'DEMO' AS demo, "
                                + "CASE WHEN t.kind = 'DEMO' THEN (SELECT count(*) FROM orders o WHERE o.tenant_id = t.id) "
                                + "+ (SELECT count(*) FROM payment p WHERE p.tenant_id = t.id) >= demo_row_cap() ELSE FALSE END AS full "
                                + "FROM tenant t WHERE t.id = ? AND t.disabled_at IS NULL")) {
                    ps.setObject(1, tenantId);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next()
                                ? Optional.of(new Intake(new TenantMoney(rs.getString(1).strip(), rs.getInt(2)), rs.getBoolean(3), rs.getBoolean(4)))
                                : Optional.<Intake>empty();
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the tenant", e);
        }
    }
}
