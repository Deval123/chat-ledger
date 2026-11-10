package dev.chatledger.demo.web;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The application's two calls into the demo functions of V020, on its own login: the deployment's mode, and one lease. It reads no tenant
 * table and writes nothing itself; {@code demo_claim} decides, in the database, which ready demo workspace a visitor gets, or none.
 */
@Component
public class DemoLeases {

    /** A leased workspace: the tenant and its seller, as the database returned them. */
    public record Lease(UUID tenantId, UUID sellerId) {
    }

    private final DataSource dataSource;

    public DemoLeases(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** {@code REGULAR} or {@code DEMO}. */
    public String mode() {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT demo_mode()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the deployment mode", e);
        }
    }

    /** The oldest fresh ready workspace, now leased for {@code lease}, or empty if none is ready (or this is not a demo deployment). */
    public Optional<Lease> claim(Duration lease) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT out_tenant_id, out_user_id FROM demo_claim(CAST(? AS INTERVAL))")) {
            ps.setString(1, lease.toSeconds() + " seconds");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Lease(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not lease a demo workspace", e);
        }
    }
}
