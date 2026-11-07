package dev.chatledger.web;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real Spring context (no hand-built Flyway) against PostgreSQL and
 * proves that Spring Boot runs the migrations on startup.
 *
 * <p>Flyway connects as the migrate role ({@code spring.flyway.user}); the
 * application datasource connects as the app login. If Spring Boot's Flyway
 * auto-configuration is not on the classpath (Boot 4 split it out of
 * {@code flyway-core} into {@code spring-boot-starter-flyway}), no migration
 * runs, there is no {@link Flyway} bean, and the V008 triggers do not exist,
 * so this test fails.
 *
 * <p>Requirements: 4.2 (append-only ledger). Task: 2.3.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FlywayStartupIT {

    private static final String MIGRATE_USER = "chatledger_migrate";
    private static final String MIGRATE_PASS = "migrate_startup_secret";
    private static final String APP_USER = "chatledger_app_login";
    private static final String APP_PASS = "app_startup_secret";

    @SuppressWarnings("resource") // lifetime managed by the JVM / Ryuk
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine")
                    .withDatabaseName("chatledger_startup")
                    .withUsername("tc_superuser")
                    .withPassword("tc_super_secret");

    // Started before the Spring context is built; the roles must exist first
    // because production creates them in docker/initdb/01_create_roles.sh.
    static {
        POSTGRES.start();
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE ROLE \"" + MIGRATE_USER + "\" LOGIN PASSWORD '"
                    + MIGRATE_PASS + "' CREATEDB NOSUPERUSER NOCREATEROLE");
            s.execute("ALTER DATABASE chatledger_startup OWNER TO \"" + MIGRATE_USER + "\"");
            s.execute("CREATE ROLE chatledger_app NOLOGIN NOSUPERUSER NOCREATEROLE");
            s.execute("CREATE ROLE \"" + APP_USER + "\" LOGIN PASSWORD '"
                    + APP_PASS + "' NOSUPERUSER NOCREATEROLE NOCREATEDB");
            s.execute("GRANT chatledger_app TO \"" + APP_USER + "\"");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not bootstrap roles", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Application datasource: the app role only.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASS);
        // Flyway: the migrate role only.
        registry.add("spring.flyway.user", () -> MIGRATE_USER);
        registry.add("spring.flyway.password", () -> MIGRATE_PASS);
        registry.add("app.secret", () -> "startup-test-secret-not-for-production");
    }

    // Fails the context load with a missing bean when the Flyway
    // auto-configuration is absent.
    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Test
    @org.junit.jupiter.api.DisplayName("Spring Boot ran every migration on startup")
    void flyway_applied_all_migrations_on_startup() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("the datasource connects as the app role, not the migrate role")
    void datasource_uses_the_app_role() throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT current_user")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo(APP_USER);
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("the V008 triggers exist on ledger_entry and ledger_txn")
    void v008_triggers_exist_after_startup() throws SQLException {
        List<String> triggers = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT tgname FROM pg_trigger "
                     + "WHERE tgrelid IN ('ledger_entry'::regclass, 'ledger_txn'::regclass) "
                     + "AND NOT tgisinternal")) {
            while (rs.next()) {
                triggers.add(rs.getString(1));
            }
        }
        assertThat(triggers).containsExactlyInAnyOrder(
                // V008: immutability
                "trg_ledger_entry_no_update_delete",
                "trg_ledger_entry_no_truncate",
                "trg_ledger_txn_no_update_delete",
                "trg_ledger_txn_no_truncate",
                // V009: tenant consistency and deferred balance check
                "trg_ledger_entry_same_tenant",
                "trg_ledger_entry_balanced",
                "trg_ledger_txn_balanced");
    }
}
