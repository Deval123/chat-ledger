package dev.chatledger.ledger;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real docker/initdb/01_create_roles.sh inside a PostgreSQL container,
 * exactly as docker-entrypoint-initdb.d does, with credentials that contain
 * quotes, backslashes, dollar signs and semicolons.
 *
 * <p>Proves the script is safe against special characters and that the roles it
 * creates are least-privilege (no superuser, no CREATEDB, no CREATEROLE).
 *
 * <p>Requirement: 9.4 (secrets from environment variables). Task: 2.3.
 */
@Testcontainers
class InitScriptIT {

    private static final String MIGRATE_USER = "chatledger_migrate";
    private static final String APP_USER = "chatledger_app_login";
    // Deliberately hostile: single and double quotes, backslash, $$, semicolon, SQL comment.
    private static final String MIGRATE_PASS = "m'ig\"r\\ate$$;-- p@ss";
    private static final String APP_PASS = "a'pp\"\\$$); DROP ROLE x;-- q";

    private static final Path SCRIPT = Path.of("..", "docker", "initdb", "01_create_roles.sh")
            .toAbsolutePath().normalize();

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine")
                    .withDatabaseName("chatledger_init")
                    .withUsername("tc_superuser")
                    .withPassword("tc_super_secret")
                    .withEnv("MIGRATE_USER", MIGRATE_USER)
                    .withEnv("MIGRATE_PASSWORD", MIGRATE_PASS)
                    .withEnv("APP_LOGIN_USER", APP_USER)
                    .withEnv("APP_LOGIN_PASSWORD", APP_PASS)
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(SCRIPT, 0755),
                            "/docker-entrypoint-initdb.d/01_create_roles.sh");

    @Test
    void script_file_is_executable_on_the_host() {
        assertThat(SCRIPT.toFile()).exists().isExecutable();
    }

    @Test
    void both_logins_work_with_hostile_passwords() throws SQLException {
        try (Connection c = connect(MIGRATE_USER, MIGRATE_PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT current_user")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo(MIGRATE_USER);
        }
        try (Connection c = connect(APP_USER, APP_PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT current_user")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo(APP_USER);
        }
    }

    @Test
    void no_role_has_superuser_createdb_or_createrole() throws SQLException {
        try (Connection c = connect(MIGRATE_USER, MIGRATE_PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolreplication "
                     + "FROM pg_roles WHERE rolname IN ('" + MIGRATE_USER + "','"
                     + APP_USER + "','chatledger_app')")) {
            int seen = 0;
            while (rs.next()) {
                seen++;
                String name = rs.getString("rolname");
                assertThat(rs.getBoolean("rolsuper")).as(name + " superuser").isFalse();
                assertThat(rs.getBoolean("rolcreatedb")).as(name + " createdb").isFalse();
                assertThat(rs.getBoolean("rolcreaterole")).as(name + " createrole").isFalse();
                assertThat(rs.getBoolean("rolreplication")).as(name + " replication").isFalse();
            }
            assertThat(seen).isEqualTo(3);
        }
    }

    @Test
    void migrate_role_owns_the_database_and_app_login_is_in_the_group() throws SQLException {
        try (Connection c = connect(MIGRATE_USER, MIGRATE_PASS);
             Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery(
                    "SELECT pg_get_userbyid(datdba) FROM pg_database "
                    + "WHERE datname = current_database()")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo(MIGRATE_USER);
            }
            try (ResultSet rs = s.executeQuery(
                    "SELECT pg_has_role('" + APP_USER + "', 'chatledger_app', 'USAGE')")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue();
            }
        }
    }

    private static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, password);
    }
}
