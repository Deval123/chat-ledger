package dev.chatledger.ledger;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * One PostgreSQL container shared by the integration tests of a JVM, set up the
 * way production is: a bootstrap superuser creates the roles, Flyway runs as the
 * non-superuser migrate role, and the code under test connects as the
 * application login (INSERT and SELECT only, subject to row-level security).
 *
 * <p>Ledger rows can never be deleted, so tests isolate themselves with a fresh
 * tenant instead of cleaning up.
 *
 * <p>Credentials are random for each run. They protect a container that lives for
 * seconds, but a fixed password in the repository would still be a fixed password
 * in the repository.
 */
public final class LedgerDatabase {

    public static final String MIGRATE_USER = "chatledger_migrate";
    public static final String APP_USER = "chatledger_app_login";

    private static final String MIGRATE_PASS = UUID.randomUUID().toString();
    private static final String APP_PASS = UUID.randomUUID().toString();

    private static LedgerDatabase instance;

    private final PostgreSQLContainer<?> container;

    public static synchronized LedgerDatabase get() {
        if (instance == null) {
            instance = new LedgerDatabase();
        }
        return instance;
    }

    private LedgerDatabase() {
        container = newContainer();
        container.start();
        bootstrapRoles(container);
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), MIGRATE_USER, MIGRATE_PASS)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** A new, unstarted PostgreSQL container with a random superuser password. */
    @SuppressWarnings("resource") // lifetime managed by Testcontainers (Ryuk)
    public static PostgreSQLContainer<?> newContainer() {
        return new PostgreSQLContainer<>("postgres:16.4-alpine")
                .withDatabaseName("chatledger_ledger")
                .withUsername("tc_superuser")
                .withPassword(UUID.randomUUID().toString());
    }

    /**
     * Creates the roles the way docker/initdb/01_create_roles.sh does: a non-superuser
     * migrate role that owns the database, the application group role, and the
     * application login.
     */
    public static void bootstrapRoles(PostgreSQLContainer<?> started) {
        try (Connection c = DriverManager.getConnection(
                started.getJdbcUrl(), started.getUsername(), started.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE ROLE \"" + MIGRATE_USER + "\" LOGIN PASSWORD '" + MIGRATE_PASS
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION");
            s.execute("ALTER DATABASE chatledger_ledger OWNER TO \"" + MIGRATE_USER + "\"");
            s.execute("CREATE ROLE chatledger_app NOLOGIN NOSUPERUSER NOCREATEROLE");
            s.execute("CREATE ROLE \"" + APP_USER + "\" LOGIN PASSWORD '" + APP_PASS
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE");
            s.execute("GRANT chatledger_app TO \"" + APP_USER + "\"");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not bootstrap roles", e);
        }
    }

    /**
     * A Flyway configured as the migrate role against the given started container.
     *
     * @param targetVersion migrate only up to this version, or null for the latest
     */
    public static Flyway flywayFor(PostgreSQLContainer<?> started, String targetVersion) {
        var configuration = Flyway.configure()
                .dataSource(started.getJdbcUrl(), MIGRATE_USER, MIGRATE_PASS)
                .locations("classpath:db/migration");
        if (targetVersion != null) {
            configuration.target(targetVersion);
        }
        return configuration.load();
    }

    /** Connects as the application role: the identity the ledger code runs as. */
    public DataSource appDataSource() {
        return dataSource(APP_USER, APP_PASS);
    }

    /** Connects as the schema owner. Only for test set-up that the app role may not do. */
    public DataSource migrateDataSource() {
        return dataSource(MIGRATE_USER, MIGRATE_PASS);
    }

    /** Connects as the bootstrap superuser. Only for creating probe roles in tests. */
    public DataSource superDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(container.getJdbcUrl());
        ds.setUser(container.getUsername());
        ds.setPassword(container.getPassword());
        return ds;
    }

    public String jdbcUrl() {
        return container.getJdbcUrl();
    }

    /** Inserts a tenant row (the app role has only SELECT on tenant) and returns its id. */
    public UUID createTenant() {
        UUID id = UUID.randomUUID();
        try (Connection c = migrateDataSource().getConnection();
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenant(id, name, currency, scale, slug) VALUES ('" + id
                    + "'::uuid, 'Test seller', 'XAF', 0, 'shop-" + id.toString().replace("-", "").substring(0, 12) + "')");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create tenant", e);
        }
        return id;
    }

    private DataSource dataSource(String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(container.getJdbcUrl());
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }
}
