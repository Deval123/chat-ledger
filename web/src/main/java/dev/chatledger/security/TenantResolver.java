package dev.chatledger.security;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns the workspace code typed at login into a tenant id. It is the one database call made
 * before any tenant is known, and it goes through {@code resolve_tenant_for_login}, a
 * SECURITY DEFINER function that returns only the id. It cannot list tenants or read their data.
 */
@Component
public class TenantResolver {

    private final DataSource dataSource;

    public TenantResolver(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** The tenant for an enabled workspace, or empty. The code must already be normalised. */
    public Optional<UUID> resolve(String workspace) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT resolve_tenant_for_login(?)")) {
            ps.setString(1, workspace);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Optional.ofNullable(rs.getObject(1, UUID.class));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not resolve the workspace", e);
        }
    }
}
