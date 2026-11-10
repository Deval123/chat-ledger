package dev.chatledger.demo.ops;

import dev.chatledger.ledger.LedgerDatabase;
import dev.chatledger.security.PasswordHashing;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The demo deployment's own database for the integration tests (task 9, D1): a second database in the shared container, migrated, and
 * moved to DEMO mode once by the demo operator's {@code demo_init}. Its pool is changed only through the V020 functions, as production
 * does; the seed goes through the real services on the application login. Tests run one after another in a JVM, so a test that needs to
 * know which workspace {@code demo_claim} hands out first retires every ready one ({@link #retireEverythingWaiting}).
 */
public final class DemoWorld {

    public static final LedgerDatabase DB = LedgerDatabase.demo();
    /** One secret for the seed and the web context of these tests, as production has one. */
    public static final String SECRET = UUID.randomUUID() + "" + UUID.randomUUID();

    static final com.zaxxer.hikari.HikariDataSource APP = pool();
    private static volatile boolean initialised;

    private static com.zaxxer.hikari.HikariDataSource pool() {
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setDataSource(DB.appDataSource());
        // Small and idle-free: the demo database shares the test cluster's connection limit with every other test context.
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(0);
        config.setIdleTimeout(10_000);
        config.setConnectionTimeout(60_000);
        return new com.zaxxer.hikari.HikariDataSource(config);
    }

    public record Workspace(UUID tenant, UUID seller) {
    }

    private DemoWorld() {
    }

    public static synchronized void init() {
        if (!initialised) {
            scalar(DB.demoOperatorDataSource(), "SELECT demo_init()");
            initialised = true;
        }
    }

    public static DemoCommand.Services services() {
        return DemoCommand.services(APP, SECRET, Clock.systemUTC());
    }

    public static DemoRefresh refresh() {
        return new DemoRefresh(DB.demoOperatorDataSource(), services().seeder(), PasswordHashing.create());
    }

    /** A new workspace, seeded and ready, made the way {@code demo-refresh} makes one. */
    public static Workspace seeded() {
        init();
        Workspace w = created();
        catalog(w.tenant());
        services().seeder().seed(w.tenant(), w.seller());
        if (!"t".equals(scalar(DB.demoOperatorDataSource(), "SELECT demo_mark_ready(?, ?, ?)", w.tenant(), 10, 8))) {
            throw new IllegalStateException("the seed did not reach its counts");
        }
        return w;
    }

    /** A new workspace in SEEDING, with nothing in it yet. */
    public static Workspace created() {
        init();
        try (Connection c = DB.demoOperatorDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT out_tenant_id, out_user_id FROM demo_create_tenant(?, 50)")) {
            ps.setString(1, PasswordHashing.create().encode(UUID.randomUUID().toString()));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("the pool is full: a test left too many workspaces waiting");
                }
                return new Workspace(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void catalog(UUID tenant) {
        scalar(DB.demoOperatorDataSource(), "SELECT demo_seed_catalog(?, CAST(? AS JSONB))", tenant, DemoRefresh.catalogJson());
    }

    /** Retires every workspace that is waiting (ready or seeding), so the next claim gets the next one made. */
    public static void retireEverythingWaiting() {
        init();
        for (String id : column(DB.demoOperatorDataSource(),
                "SELECT demo_due_for_retirement(CAST('0 seconds' AS INTERVAL), CAST('0 seconds' AS INTERVAL))")) {
            scalar(DB.demoOperatorDataSource(), "SELECT demo_retire(?)", UUID.fromString(id));
        }
    }

    /** Retires one workspace (a test cleans up after itself; nothing is deleted). */
    public static void retire(Workspace w) {
        scalar(DB.demoOperatorDataSource(), "SELECT demo_retire(?)", w.tenant());
    }

        /** A claim as the application makes it. */
    public static Workspace claim() {
        try (Connection c = DB.appDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT out_tenant_id, out_user_id FROM demo_claim(CAST('30 minutes' AS INTERVAL))");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new Workspace(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The first column of the first row as text, or null. */
    public static String scalar(DataSource ds, String sql, Object... args) {
        List<String> rows = column(ds, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static List<String> column(DataSource ds, String sql, Object... args) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            List<String> out = new ArrayList<>();
            if (ps.execute()) {
                try (ResultSet rs = ps.getResultSet()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** As the owner of the demo database (not bound by row-level security): to read back what the tests check. */
    public static List<String> owner(String sql, Object... args) {
        return column(DB.migrateDataSource(), sql, args);
    }

    public static String ownerOne(String sql, Object... args) {
        return scalar(DB.migrateDataSource(), sql, args);
    }
}
