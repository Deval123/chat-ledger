package dev.chatledger.demo.ops;

import dev.chatledger.demo.DemoCatalog;
import dev.chatledger.demo.DemoSeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * One pass of the demo's upkeep (task 9, D6 and D24), on the demo operator's login for the pool and the application's login for the seed:
 *
 * <ol>
 *   <li>Take the refresh lock (a session lock on this pass's own connection). If another runner holds it, do nothing.</li>
 *   <li>Retire every workspace that is due: leases that ended, ready ones older than three hours, seeds stuck for ten minutes.</li>
 *   <li>Top the pool up: create a workspace, write its catalog, seed it through the ingestion services, mark it ready.</li>
 * </ol>
 *
 * <p>Nothing is deleted. Every write goes through a V020 function that checks the deployment mode and the workspace's kind itself.
 */
public final class DemoRefresh {

    private static final Logger LOG = LoggerFactory.getLogger("demo.refresh");

    public static final int MIN_POOL = 10;
    public static final int MAX_POOL = 50;
    public static final int DEFAULT_POOL = 30;

    /** What one pass did. {@code skipped} means another runner held the lock. */
    public record Report(boolean skipped, int retired, int created, int failed) {
    }

    private final DataSource operator;
    private final DemoSeeder seeder;
    private final PasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();

    public DemoRefresh(DataSource operator, DemoSeeder seeder, PasswordEncoder encoder) {
        this.operator = operator;
        this.seeder = seeder;
        this.encoder = encoder;
    }

    public Report run(int poolSize) {
        if (poolSize < MIN_POOL || poolSize > MAX_POOL) {
            throw new IllegalArgumentException("the pool size must be between " + MIN_POOL + " and " + MAX_POOL);
        }
        try (Connection c = operator.getConnection()) {
            if (!lock(c)) {
                LOG.info("demo refresh skipped: another runner holds the lock");
                return new Report(true, 0, 0, 0);
            }
            int retired = 0;
            for (UUID tenant : due(c)) {
                if (retire(c, tenant)) {
                    retired++;
                }
            }
            int created = 0;
            int failed = 0;
            for (int i = 0; i < poolSize; i++) {
                Workspace w = create(c, poolSize);
                if (w == null) {
                    break;   // the pool is full
                }
                try {
                    catalog(c, w.tenant());
                    seeder.seed(w.tenant(), w.seller());
                    if (markReady(c, w.tenant())) {
                        created++;
                    } else {
                        failed++;
                        break;
                    }
                } catch (RuntimeException e) {
                    // The workspace stays in SEEDING and is retired by a later pass. No message text in the log.
                    LOG.warn("demo seed failed tenant={} error={}", w.tenant(), e.getClass().getSimpleName());
                    failed++;
                    break;
                }
            }
            LOG.info("demo refresh done retired={} created={} failed={}", retired, created, failed);
            return new Report(false, retired, created, failed);
        } catch (SQLException e) {
            throw new IllegalStateException("The demo refresh could not use the database (SQLSTATE " + e.getSQLState() + ")", null);
        }
    }

    private record Workspace(UUID tenant, UUID seller) {
    }

    private static List<UUID> due(Connection c) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT demo_due_for_retirement(CAST('3 hours' AS INTERVAL), CAST('10 minutes' AS INTERVAL))");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getObject(1, UUID.class));
            }
        }
        return out;
    }

    private static boolean retire(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT demo_retire(?)")) {
            ps.setObject(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /** A new workspace, or null if the pool is full. Its seller's password is random, never stored or shown: only the hash leaves here. */
    private Workspace create(Connection c, int poolSize) throws SQLException {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String hash = encoder.encode(Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        java.util.Arrays.fill(secret, (byte) 0);
        try (PreparedStatement ps = c.prepareStatement("SELECT out_tenant_id, out_user_id FROM demo_create_tenant(?, ?)")) {
            ps.setString(1, hash);
            ps.setInt(2, poolSize);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Workspace(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)) : null;
            }
        }
    }

    private static void catalog(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT demo_seed_catalog(?, CAST(? AS JSONB))")) {
            ps.setObject(1, tenant);
            ps.setString(2, catalogJson());
            ps.executeQuery().close();
        }
    }

    /** The catalog as the JSON array {@code demo_seed_catalog} reads. The names are this application's own constants. */
    static String catalogJson() {
        StringBuilder sb = new StringBuilder("[");
        for (DemoCatalog.Row row : DemoCatalog.rows()) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append("{\"name_key\":").append(json(row.nameKey())).append(",\"display_name\":").append(json(row.displayName()))
                    .append(",\"unit_minor\":").append(row.unitMinor()).append('}');
        }
        return sb.append(']').toString();
    }

    static String json(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static boolean markReady(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT demo_mark_ready(?, ?, ?)")) {
            ps.setObject(1, tenant);
            ps.setInt(2, DemoSeed.ORDERS);
            ps.setInt(3, DemoSeed.PAYMENTS);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static boolean lock(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT demo_refresh_lock()"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}
