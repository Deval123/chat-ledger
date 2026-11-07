package dev.chatledger.orders;

import dev.chatledger.tenancy.TenantScope;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the tenant's price list. A lookup is an equality on the item's comparison key ({@code name_key = ANY (?)} with
 * the keys bound as an array): never {@code LIKE}, so a {@code %} or {@code _} typed in an order cannot match other
 * items. The application role has no write privilege on the catalog.
 */
@Repository
public class CatalogRepository {

    private final TenantScope scope;

    public CatalogRepository(DataSource dataSource) {
        this.scope = new TenantScope(dataSource);
    }

    /** Price in minor units for each of the given keys that is an enabled catalog item of the tenant. */
    public Map<String, Long> prices(UUID tenantId, Collection<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        try {
            return scope.call(tenantId, c -> {
                Map<String, Long> found = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT name_key, unit_minor FROM catalog_item "
                                + "WHERE tenant_id = ? AND disabled_at IS NULL AND name_key = ANY (?)")) {
                    ps.setObject(1, tenantId);
                    ps.setArray(2, c.createArrayOf("text", keys.toArray()));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            found.put(rs.getString(1), rs.getLong(2));
                        }
                    }
                }
                return found;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the catalog", e);
        }
    }

    /** Number of items the tenant can see (for tests and monitoring). */
    public long count(UUID tenantId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM catalog_item WHERE tenant_id = ?")) {
                    ps.setObject(1, tenantId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the catalog", e);
        }
    }
}
