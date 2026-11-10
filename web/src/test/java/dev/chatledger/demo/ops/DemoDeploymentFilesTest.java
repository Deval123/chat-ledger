package dev.chatledger.demo.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo deployment's files say what the design says (task 9, D7, D24): the entrypoint runs the refresh loop as its own process and
 * starts the web process without the demo operator's credentials; the role script creates the operator's login only when asked; the
 * example environment holds placeholders only. No database.
 */
class DemoDeploymentFilesTest {

    private static final Path ROOT = Path.of("..");

    @Test
    void the_entrypoint_runs_two_processes_and_the_web_one_never_holds_the_operator_login() throws IOException {
        String script = Files.readString(ROOT.resolve("docker/demo-entrypoint.sh"));
        assertThat(script).contains("demo-refresh --pool \"$POOL\" --every \"$EVERY\"")
                .contains("env -u DEMO_DB_USER -u DEMO_DB_PASSWORD java -jar \"$JAR\" --app.demo.enabled=true")
                .contains("set -eu");
        assertThat(Files.isExecutable(ROOT.resolve("docker/demo-entrypoint.sh"))).isTrue();
    }

    @Test
    void the_role_script_creates_the_operator_group_always_and_its_login_only_when_asked() throws IOException {
        String roles = Files.readString(ROOT.resolve("docker/initdb/01_create_roles.sh"));
        assertThat(roles).contains("CREATE ROLE chatledger_demo_operator NOLOGIN").contains("if [ -n \"${DEMO_DB_USER:-}\" ]")
                .contains("GRANT chatledger_demo_operator TO %I");
        String example = Files.readString(ROOT.resolve(".env.example"));
        assertThat(example).contains("# DEMO_DB_USER=").contains("# DEMO_DB_PASSWORD=<replace-with-strong-demo-operator-password>");
    }
}
