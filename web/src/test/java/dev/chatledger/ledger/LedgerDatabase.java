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

    public static final String PROVISION_USER = "chatledger_provisioner_login";
    public static final String DEMO_USER = "chatledger_demo_login";
    /** The demo deployment's own database, in the same container (task 9, D1). Roles are shared by the cluster. */
    public static final String DEMO_DATABASE = "chatledger_demo";

    private static final String PROVISION_PASS = UUID.randomUUID().toString();
    private static final String MIGRATE_PASS = UUID.randomUUID().toString();
    private static final String APP_PASS = UUID.randomUUID().toString();
    private static final String DEMO_PASS = UUID.randomUUID().toString();

    private static LedgerDatabase instance;
    private static LedgerDatabase demo;

    private final PostgreSQLContainer<?> container;
    private final String jdbcUrl;

    public static synchronized LedgerDatabase get() {
        if (instance == null) {
            instance = new LedgerDatabase();
        }
        return instance;
    }

    /**
     * A second database in the same container, migrated the same way, for the demo deployment's tests: its mode moves to DEMO and never
     * back, which must not reach the tests of a regular deployment.
     */
    public static synchronized LedgerDatabase demo() {
        if (demo == null) {
            LedgerDatabase main = get();
            try (Connection c = DriverManager.getConnection(main.container.getJdbcUrl(), main.container.getUsername(), main.container.getPassword());
                 Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE " + DEMO_DATABASE + " OWNER \"" + MIGRATE_USER + "\"");
            } catch (SQLException e) {
                throw new IllegalStateException("Could not create the demo database", e);
            }
            demo = new LedgerDatabase(main.container, main.container.getJdbcUrl().replace("/chatledger_ledger", "/" + DEMO_DATABASE));
        }
        return demo;
    }

    private LedgerDatabase() {
        container = newContainer();
        container.start();
        bootstrapRoles(container);
        jdbcUrl = container.getJdbcUrl();
        migrate();
    }

    private LedgerDatabase(PostgreSQLContainer<?> container, String jdbcUrl) {
        this.container = container;
        this.jdbcUrl = jdbcUrl;
        migrate();
    }

    private void migrate() {
        Flyway.configure()
                .dataSource(jdbcUrl, MIGRATE_USER, MIGRATE_PASS)
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
            // Provisioning: group role first, because V013 grants EXECUTE to it only if it exists.
            s.execute("CREATE ROLE chatledger_provisioner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
            s.execute("CREATE ROLE \"" + PROVISION_USER + "\" LOGIN PASSWORD '" + PROVISION_PASS
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE");
            s.execute("GRANT chatledger_provisioner TO \"" + PROVISION_USER + "\"");
            // The demo operator: group role first, because V020 grants EXECUTE to it only if it exists (task 9).
            s.execute("CREATE ROLE chatledger_demo_operator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
            s.execute("CREATE ROLE \"" + DEMO_USER + "\" LOGIN PASSWORD '" + DEMO_PASS
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE");
            s.execute("GRANT chatledger_demo_operator TO \"" + DEMO_USER + "\"");
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

    /** Connects as the provisioning login: EXECUTE on the provisioning functions only. */
    public DataSource provisionerDataSource() {
        return dataSource(PROVISION_USER, PROVISION_PASS);
    }

    /** Connects as the demo operator login: EXECUTE on the demo functions only. */
    public DataSource demoOperatorDataSource() {
        return dataSource(DEMO_USER, DEMO_PASS);
    }

    public String demoOperatorPassword() {
        return DEMO_PASS;
    }

    public String appPassword() {
        return APP_PASS;
    }

    public String provisionerPassword() {
        return PROVISION_PASS;
    }

    /** Connects as the schema owner. Only for test set-up that the app role may not do. */
    public DataSource migrateDataSource() {
        return dataSource(MIGRATE_USER, MIGRATE_PASS);
    }

    /** Connects as the bootstrap superuser. Only for creating probe roles in tests. */
    public DataSource superDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl);
        ds.setUser(container.getUsername());
        ds.setPassword(container.getPassword());
        return ds;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    /** Inserts a tenant row (the app role has only SELECT on tenant) and returns its id. */
    public UUID createTenant() {
        return createTenant("XAF", 0);
    }

    /** Inserts a tenant with the given currency and number of decimal places. */
    public UUID createTenant(String currency, int scale) {
        UUID id = UUID.randomUUID();
        try (Connection c = migrateDataSource().getConnection();
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenant(id, name, currency, scale, slug) VALUES ('" + id
                    + "'::uuid, 'Test seller', '" + currency + "', " + scale + ", 'shop-" + id.toString().replace("-", "").substring(0, 12) + "')");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create tenant", e);
        }
        return id;
    }

    private DataSource dataSource(String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }
}
