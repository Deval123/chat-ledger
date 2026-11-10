package dev.chatledger.deploy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evidence behind the one accepted finding (register 155): {@code deploy/check-gosu.sh}.
 *
 * <p>On the PostgreSQL image {@code compose.yaml} pins, every check passes: the db service starts as 70:70, read-only, with no
 * capabilities and no-new-privileges; the image's three calls of {@code gosu} run only when the uid is 0; nothing in the image is
 * setuid; the image's own entrypoint, traced with those settings, never executes {@code gosu} and the server comes up as uid 70; and,
 * as a control, the trace started as root does show {@code gosu}.
 *
 * <p>And each check can fail: on a compose file that starts db as root, on an image whose entrypoint calls {@code gosu} without the
 * guard, on an image where {@code gosu} is setuid, and on an image with a fourth call, and on an image with no {@code gosu} call (the control then
 * cannot see it). The four broken images are built locally from the pinned one and removed afterwards. Needs Docker (the integration phase).
 */
class CheckGosuIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final String UNGUARDED = "chat-ledger-gosu-test:unguarded";
    private static final String SETUID = "chat-ledger-gosu-test:setuid";
    private static final String NO_CALL = "chat-ledger-gosu-test:no-call";
    private static final String EXTRA = "chat-ledger-gosu-test:extra-call";

    @TempDir
    static Path tmp;

    record Run(int code, String out) {
    }

    private static Run run(Path dir, String stdin, String... command) throws Exception {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        if (stdin != null) {
            p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(5, TimeUnit.MINUTES)).as("finished: " + String.join(" ", command)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private static String pinnedImage() throws IOException {
        Matcher m = Pattern.compile("(?m)^    image: (postgres:\\S+@sha256:[0-9a-f]{64})$").matcher(Files.readString(ROOT.resolve("deploy/compose.yaml")));
        assertThat(m.find()).as("compose.yaml pins PostgreSQL").isTrue();
        return m.group(1);
    }

    private static void build(String tag, String step) throws Exception {
        String dockerfile = "FROM " + pinnedImage() + "\n" + step + "\n";
        Run r = run(ROOT, dockerfile, "docker", "build", "-q", "-t", tag, "-");
        assertThat(r.code()).as(r.out()).isZero();
    }

    @BeforeAll
    static void brokenImages() throws Exception {
        build(UNGUARDED, "RUN sed -i \"s/if \\[ \\\"\\$(id -u)\\\" = '0' \\]; then/if true; then/\" /usr/local/bin/docker-entrypoint.sh");
        build(SETUID, "RUN chmod u+s /usr/local/bin/gosu");
        build(NO_CALL, "RUN sed -i '/exec gosu/d' /usr/local/bin/docker-entrypoint.sh /usr/local/bin/docker-ensure-initdb.sh /usr/local/bin/docker-enforce-initdb.sh");
        build(EXTRA, "RUN printf 'exec gosu postgres true\\n' >> /usr/local/bin/docker-ensure-initdb.sh");
    }

    @AfterAll
    static void removeImages() throws Exception {
        run(ROOT, null, "docker", "image", "rm", "-f", UNGUARDED, SETUID, NO_CALL, EXTRA);
    }

    /** check-gosu.sh run from a sandbox holding a copy of the script and of compose.yaml, the latter edited by {@code edit}. */
    private static Run sandbox(java.util.function.UnaryOperator<String> edit, String... args) throws Exception {
        Path work = Files.createTempDirectory(tmp, "work");
        Path deploy = Files.createDirectories(work.resolve("deploy"));
        Files.copy(ROOT.resolve("deploy/check-gosu.sh"), deploy.resolve("check-gosu.sh"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(deploy.resolve("compose.yaml"), edit.apply(Files.readString(ROOT.resolve("deploy/compose.yaml"))));
        List<String> command = new ArrayList<>(List.of("sh", "deploy/check-gosu.sh"));
        command.addAll(List.of(args));
        return run(work, null, command.toArray(String[]::new));
    }

    @Test
    void the_evidence_that_gosu_never_runs_holds_for_the_pinned_image() throws Exception {
        Run r = run(ROOT, null, "sh", "deploy/check-gosu.sh");
        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("PASS db:    user: \"70:70\"").contains("PASS db:    read_only: true").contains("PASS db:    cap_drop: [ALL]")
                .contains("PASS db:    security_opt: [\"no-new-privileges:true\"]")
                .contains("PASS the 3 calls of gosu each run only under: if [ \"$(id -u)\" = '0' ]")
                .contains("PASS gosu is 0755, not setuid; the image has no setuid file")
                .contains("PASS as configured, gosu is never executed")
                .contains("PASS the server came up as uid 70")
                .contains("PASS control: started as root, the trace shows gosu")
                .contains("Failures: 0").doesNotContain("FAIL ");
    }

    @Test
    void a_db_service_started_as_root_or_with_a_writable_root_is_refused() throws Exception {
        Run root = sandbox(c -> c.replaceFirst("    user: \"70:70\"\n", ""));
        assertThat(root.code()).isEqualTo(1);
        assertThat(root.out()).contains("FAIL db does not have:    user: \"70:70\"");
        Run writable = sandbox(c -> c.replaceFirst("(?s)(  db:.*?)    read_only: true\n", "$1    read_only: false\n"));
        assertThat(writable.code()).isEqualTo(1);
        assertThat(writable.out()).contains("FAIL db does not have:    read_only: true");
    }

    @Test
    void an_unguarded_call_is_refused_and_the_trace_sees_gosu_run() throws Exception {
        Run r = sandbox(c -> c, UNGUARDED);
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("FAIL gosu calls: 3 found, 2 under the root-only condition").contains("FAIL as configured, gosu was executed")
                .contains("FAIL the server did not come up as uid 70");
    }

    @Test
    void a_fourth_call_anywhere_is_refused_even_if_the_three_known_ones_are_guarded() throws Exception {
        Run r = sandbox(c -> c, EXTRA);
        assertThat(r.code()).isEqualTo(1);
        // The two helper scripts may be one file under two names, so the added call can be counted once or twice.
        assertThat(r.out()).containsPattern("FAIL gosu calls: [45] found, 3 under the root-only condition");
    }

    @Test
    void a_setuid_gosu_is_refused() throws Exception {
        Run r = sandbox(c -> c, SETUID);
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("FAIL setuid check:");
    }

    @Test
    void the_control_fails_when_the_trace_cannot_see_gosu() throws Exception {
        Run r = sandbox(c -> c, NO_CALL);
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("FAIL gosu calls: 0 found").contains("FAIL control: the trace cannot see gosu");
    }
}
