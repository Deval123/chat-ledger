package dev.chatledger.deploy;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code deploy/make-secrets.sh} (task 13, D6), run as root in a Linux container as on the VPS: the layout and modes the compose file
 * relies on (root:70 for PostgreSQL, root:65532 for the image and cloudflared, 0440, directories 0700), 64 hex characters per value with
 * no newline, the shared values written twice and equal, every other value distinct; it never overwrites, never runs as another user,
 * never prints a value; the tunnel token is refused unless it is one line of token characters.
 */
@Testcontainers
class MakeSecretsScriptIT {

    private static final Path SCRIPT = ComposeFileTest.DEPLOY.resolve("make-secrets.sh").toAbsolutePath().normalize();
    private static final List<String> DB = List.of("app_password", "demo_operator_password", "migrate_password", "pg_superuser_password",
            "provisioner_password");
    private static final List<String> APP = List.of("app_password", "app_secret", "demo_operator_password", "migrate_password");
    /** A made-up token of the right shape: base64 of made-up JSON. */
    private static final String TOKEN = "eyJhIjoibWFkZS11cCIsInQiOiJtYWRlLXVwIiwicyI6Im1hZGUtdXAifQ==";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> HOST = new GenericContainer<>(
            DockerImageName.parse("alpine:3.22.8@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f"))
            .withCopyFileToContainer(MountableFile.forHostPath(SCRIPT, 0644), "/s/make-secrets.sh")
            .withCommand("sleep", "infinity");

    private static ExecResult sh(String script) throws IOException, InterruptedException {
        return HOST.execInContainer("sh", "-c", script);
    }

    private static String out(String script) throws IOException, InterruptedException {
        return sh(script).getStdout().strip();
    }

    @Test
    void writes_every_value_once_with_the_modes_the_compose_file_relies_on() throws Exception {
        ExecResult run = sh("sh /s/make-secrets.sh /opt/a");
        assertThat(run.getExitCode()).as(run.getStderr()).isZero();
        for (String dir : List.of("/opt/a", "/opt/a/db", "/opt/a/app")) {
            assertThat(out("stat -c '%u:%g %a' " + dir)).as(dir).isEqualTo("0:0 700");
        }
        assertThat(out("ls /opt/a/db").lines()).containsExactlyElementsOf(DB);
        assertThat(out("ls /opt/a/app").lines()).containsExactlyElementsOf(APP);
        Set<String> values = new HashSet<>();
        for (String f : DB) {
            assertThat(out("stat -c '%u:%g %a %s' /opt/a/db/" + f)).as(f).isEqualTo("0:70 440 64");
            values.add(out("cat /opt/a/db/" + f));
        }
        for (String f : APP) {
            assertThat(out("stat -c '%u:%g %a %s' /opt/a/app/" + f)).as(f).isEqualTo("0:65532 440 64");
            values.add(out("cat /opt/a/app/" + f));
        }
        assertThat(values).as("five distinct db values plus app_secret").hasSize(6).allMatch(v -> v.matches("[0-9a-f]{64}"));
        for (String shared : List.of("app_password", "demo_operator_password", "migrate_password")) {
            assertThat(sh("cmp /opt/a/db/" + shared + " /opt/a/app/" + shared).getExitCode()).as(shared + " written twice, equal").isZero();
        }
        assertThat(sh("tail -c 1 /opt/a/db/app_password | od -An -c").getStdout()).as("no trailing newline").doesNotContain("\\n");
        for (String v : values) {
            assertThat(run.getStdout() + run.getStderr()).as("no value is printed").doesNotContain(v);
        }
        assertThat(out("ls -a /opt")).as("no temporary directory left").doesNotContain(".new.");
    }

    @Test
    void refuses_to_overwrite_to_run_as_another_user_or_to_guess_a_path() throws Exception {
        assertThat(sh("sh /s/make-secrets.sh /opt/b").getExitCode()).isZero();
        String before = out("cat /opt/b/db/* /opt/b/app/* | sha256sum");
        ExecResult again = sh("sh /s/make-secrets.sh /opt/b");
        assertThat(again.getExitCode()).isEqualTo(1);
        assertThat(again.getStderr()).contains("Refused: /opt/b exists");
        assertThat(out("cat /opt/b/db/* /opt/b/app/* | sha256sum")).as("nothing changed").isEqualTo(before);

        ExecResult relative = sh("cd /opt && sh /s/make-secrets.sh c");
        assertThat(relative.getExitCode()).isEqualTo(2);
        assertThat(sh("test -e /opt/c").getExitCode()).isNotZero();

        assertThat(sh("sh /s/make-secrets.sh /opt/d /opt/e").getExitCode()).isEqualTo(2);

        ExecResult nobody = sh("su nobody -s /bin/sh -c 'sh /s/make-secrets.sh /tmp/n'");
        assertThat(nobody.getExitCode()).isEqualTo(1);
        assertThat(nobody.getStderr()).contains("run as root");
        assertThat(sh("test -e /tmp/n").getExitCode()).isNotZero();

        assertThat(sh("sh /s/make-secrets.sh /no/such/parent/x").getExitCode()).isEqualTo(1);
        assertThat(sh("test -e /no").getExitCode()).isNotZero();
    }

    @Test
    void a_failure_half_way_leaves_nothing_behind() throws Exception {
        // chown to a group that cannot exist fails after the first value is written.
        ExecResult r = sh("CHAT_LEDGER_DB_GID=not-a-group sh /s/make-secrets.sh /opt/f");
        assertThat(r.getExitCode()).isNotZero();
        assertThat(sh("test -e /opt/f").getExitCode()).as("no secrets directory").isNotZero();
        assertThat(out("ls -a /opt")).as("no temporary directory with a value in it").doesNotContain("f.new.");
    }

    @Test
    void the_tunnel_token_is_one_line_of_token_characters_written_once() throws Exception {
        ExecResult early = sh("printf '%s\\n' " + TOKEN + " | sh /s/make-secrets.sh --tunnel-token /opt/t");
        assertThat(early.getExitCode()).as("refused before the values exist").isEqualTo(1);
        assertThat(early.getStderr()).contains("Write the generated values first.");
        assertThat(sh("sh /s/make-secrets.sh /opt/t").getExitCode()).isZero();
        for (String bad : List.of("''", "'a b'", "'tok;rm'", "'tok$(id)'", "'two\\nlines'")) {
            ExecResult r = sh("printf " + bad + " | sh /s/make-secrets.sh --tunnel-token /opt/t");
            assertThat(r.getExitCode()).as(bad).isEqualTo(1);
            assertThat(sh("test -e /opt/t/app/tunnel_token").getExitCode()).as(bad + ": nothing written").isNotZero();
        }
        ExecResult tooLong = sh("head -c 4097 /dev/zero | tr '\\0' 'a' | sh /s/make-secrets.sh --tunnel-token /opt/t");
        assertThat(tooLong.getExitCode()).isEqualTo(1);

        ExecResult ok = sh("printf '%s\\n' " + TOKEN + " | sh /s/make-secrets.sh --tunnel-token /opt/t");
        assertThat(ok.getExitCode()).as(ok.getStderr()).isZero();
        assertThat(ok.getStdout() + ok.getStderr()).doesNotContain(TOKEN);
        assertThat(out("stat -c '%u:%g %a' /opt/t/app/tunnel_token")).isEqualTo("0:65532 440");
        assertThat(out("cat /opt/t/app/tunnel_token; echo .")).as("the token, without its newline").isEqualTo(TOKEN + ".");

        ExecResult again = sh("printf '%s\\n' eyJhbm90aGVy | sh /s/make-secrets.sh --tunnel-token /opt/t");
        assertThat(again.getExitCode()).isEqualTo(1);
        assertThat(out("cat /opt/t/app/tunnel_token")).as("not overwritten").isEqualTo(TOKEN);
    }
}
