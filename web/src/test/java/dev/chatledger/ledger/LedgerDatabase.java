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
 * One PostgreSQL container shared by the ledger integration tests of a JVM,
 * set up the way production is: a bootstrap superuser creates the roles, Flyway
 * runs as the non-superuser migrate role, and the code under test connects as
 * the application login (INSERT and SELECT only).
 *
 * <p>Ledger rows can never be deleted, so tests isolate themselves with a fresh
 * tenant instead of cleaning up.
 */
final class LedgerDatabase {

    static final String MIGRATE_USER = "chatledger_migrate";
    static final String MIGRATE_PASS = "ledger_migrate_secret";
    static final String APP_USER = "chatledger_app_login";
    static final String APP_PASS = "ledger_app_secret";

    private static LedgerDatabase instance;

    private final PostgreSQLContainer<?> container;

    static synchronized LedgerDatabase get() {
        if (instance == null) {
            instance = new LedgerDatabase();
        }
        return instance;
    }

    @SuppressWarnings("resource") // lifetime managed by Testcontainers (Ryuk)
    private LedgerDatabase() {
        container = new PostgreSQLContainer<>("postgres:16.4-alpine")
                .withDatabaseName("chatledger_ledger")
                .withUsername("tc_superuser")
                .withPassword("tc_super_secret");
        container.start();
        try (Connection c = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
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
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), MIGRATE_USER, MIGRATE_PASS)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** Connects as the application role: the identity the ledger code runs as. */
    DataSource appDataSource() {
        return dataSource(APP_USER, APP_PASS);
    }

    /** Connects as the schema owner. Only for test set-up that the app role may not do. */
    DataSource migrateDataSource() {
        return dataSource(MIGRATE_USER, MIGRATE_PASS);
    }

    /** Inserts a tenant row (the app role has only SELECT on tenant) and returns its id. */
    UUID createTenant() {
        UUID id = UUID.randomUUID();
        try (Connection c = migrateDataSource().getConnection();
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenant(id, name, currency, scale) VALUES ('" + id
                    + "'::uuid, 'Test seller', 'XAF', 0)");
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
