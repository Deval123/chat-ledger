package dev.chatledger.ledger;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docker/initdb/01_create_roles.sh with its passwords as files (task 13, D6): the deployment gives every login as {@code NAME_FILE},
 * a Compose secret under {@code /run/secrets/}. The values are hostile (quotes, backslash, dollar signs, semicolons) and one file ends
 * with a newline, as a hand-written file would; every login works with the value without that newline. A variable given both ways, or
 * a file that cannot be read, stops the script before it touches the database, and the value is never printed.
 */
@Testcontainers
class InitScriptFilesIT {

    private static final String MIGRATE_PASS = "m'ig\"r\\ate$$;-- " + UUID.randomUUID();
    private static final String APP_PASS = "a'pp\"\\$$); DROP ROLE x;-- " + UUID.randomUUID();
    private static final String PROVISION_PASS = "p'rov\"\\$$;-- " + UUID.randomUUID();
    private static final String DEMO_PASS = "d'emo\"\\$$;-- " + UUID.randomUUID();
    private static final Path SCRIPT = Path.of("..", "docker", "initdb", "01_create_roles.sh").toAbsolutePath().normalize();

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(LedgerDatabase.image())
            .withDatabaseName("chatledger_files")
            .withUsername("tc_superuser")
            .withPassword(UUID.randomUUID().toString())
            .withEnv("MIGRATE_USER", "chatledger_migrate")
            .withEnv("MIGRATE_PASSWORD_FILE", "/run/secrets/migrate_password")
            .withEnv("APP_LOGIN_USER", "chatledger_app_login")
            .withEnv("APP_LOGIN_PASSWORD_FILE", "/run/secrets/app_password")
            .withEnv("PROVISION_DB_USER", "chatledger_provisioner_login")
            .withEnv("PROVISION_DB_PASSWORD_FILE", "/run/secrets/provisioner_password")
            .withEnv("DEMO_DB_USER", "chatledger_demo_login")
            .withEnv("DEMO_DB_PASSWORD_FILE", "/run/secrets/demo_operator_password")
            .withCopyToContainer(Transferable.of(MIGRATE_PASS + "\n", 0444), "/run/secrets/migrate_password")
            .withCopyToContainer(Transferable.of(APP_PASS, 0444), "/run/secrets/app_password")
            .withCopyToContainer(Transferable.of(PROVISION_PASS, 0444), "/run/secrets/provisioner_password")
            .withCopyToContainer(Transferable.of(DEMO_PASS, 0444), "/run/secrets/demo_operator_password")
            .withCopyToContainer(Transferable.of("unreadable", 0000), "/run/secrets/locked")
            .withCopyToContainer(Transferable.of("first-line\nsecond-line\n", 0444), "/run/secrets/two_lines")
            .withCopyToContainer(Transferable.of("windows-line\r\n", 0444), "/run/secrets/crlf")
            .withCopyToContainer(Transferable.of("\n", 0444), "/run/secrets/empty")
            .withCopyFileToContainer(MountableFile.forHostPath(SCRIPT, 0755), "/docker-entrypoint-initdb.d/01_create_roles.sh");

    private static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, password);
    }

    private static String currentUser(String user, String password) throws SQLException {
        try (Connection c = connect(user, password); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT current_user")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void every_login_works_with_its_value_from_a_file_and_the_newline_is_not_part_of_it() throws SQLException {
        assertThat(currentUser("chatledger_migrate", MIGRATE_PASS)).isEqualTo("chatledger_migrate");
        assertThat(currentUser("chatledger_app_login", APP_PASS)).isEqualTo("chatledger_app_login");
        assertThat(currentUser("chatledger_provisioner_login", PROVISION_PASS)).isEqualTo("chatledger_provisioner_login");
        assertThat(currentUser("chatledger_demo_login", DEMO_PASS)).isEqualTo("chatledger_demo_login");
    }

    /** Runs the script again inside the running container, as the postgres user, with one variable changed. */
    private static ExecResult rerun(String assignment) throws Exception {
        return POSTGRES.execInContainer("su", "postgres", "-c",
                // The changed variable comes last, so it wins over the base value.
                "env POSTGRES_USER=tc_superuser POSTGRES_DB=chatledger_files "
                        + "MIGRATE_USER=chatledger_migrate MIGRATE_PASSWORD_FILE=/run/secrets/migrate_password "
                        + "APP_LOGIN_USER=chatledger_app_login APP_LOGIN_PASSWORD_FILE=/run/secrets/app_password "
                        + "PROVISION_DB_USER=chatledger_provisioner_login PROVISION_DB_PASSWORD_FILE=/run/secrets/provisioner_password "
                        + assignment + " bash /docker-entrypoint-initdb.d/01_create_roles.sh");
    }

    @Test
    void a_variable_given_both_ways_or_an_unreadable_file_stops_the_script_and_prints_no_value() throws Exception {
        ExecResult both = rerun("MIGRATE_PASSWORD=also-a-value");
        assertThat(both.getExitCode()).isEqualTo(1);
        assertThat(both.getStderr()).contains("Refused: both MIGRATE_PASSWORD and MIGRATE_PASSWORD_FILE are set.")
                .doesNotContain("also-a-value").doesNotContain(MIGRATE_PASS);
        assertThat(both.getStdout()).as("stopped before psql").doesNotContain("CREATE ROLE").doesNotContain("Roles for");

        ExecResult locked = rerun("DEMO_DB_USER=chatledger_demo_login DEMO_DB_PASSWORD_FILE=/run/secrets/locked");
        assertThat(locked.getExitCode()).isEqualTo(1);
        assertThat(locked.getStderr()).contains("Refused: DEMO_DB_PASSWORD_FILE does not name a readable file.").doesNotContain("unreadable");

        ExecResult missing = rerun("APP_LOGIN_PASSWORD_FILE=/run/secrets/none");
        assertThat(missing.getExitCode()).isEqualTo(1);
        assertThat(missing.getStderr()).contains("APP_LOGIN_PASSWORD_FILE does not name a readable file");

        for (String odd : new String[]{"two_lines", "crlf", "empty"}) {
            ExecResult r = rerun("MIGRATE_PASSWORD_FILE=/run/secrets/" + odd);
            assertThat(r.getExitCode()).as(odd).isEqualTo(1);
            assertThat(r.getStderr()).as(odd).contains("Refused: the file of MIGRATE_PASSWORD_FILE must hold one line.")
                    .doesNotContain("first-line").doesNotContain("windows-line");
        }

        ExecResult fine = rerun("");
        assertThat(fine.getExitCode()).as(fine.getStderr()).isZero();
        assertThat(fine.getStdout() + fine.getStderr()).doesNotContain(MIGRATE_PASS).doesNotContain(APP_PASS).doesNotContain(PROVISION_PASS);
    }
}
