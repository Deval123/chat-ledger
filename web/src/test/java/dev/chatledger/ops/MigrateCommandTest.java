package dev.chatledger.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code db-migrate} refuses before it touches a database, reads its password from a file, and never prints it (task 13, D4). */
class MigrateCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final AtomicReference<String> used = new AtomicReference<>();

    private static Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("DB_URL", "jdbc:postgresql://db:5432/chatledger");
        env.put("MIGRATE_USER", "chatledger_migrate");
        env.put("MIGRATE_PASSWORD", "owner-password-not-real");
        env.put("DB_USER", "chatledger_app_login");
        return env;
    }

    private int run(Map<String, String> env, MigrateCommand.Migrator migrator, String... args) {
        return new MigrateCommand(env, migrator, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run(args.length == 0 ? new String[]{"db-migrate"} : args);
    }

    private final MigrateCommand.Migrator recording = (url, user, password) -> {
        used.set(url + "|" + user + "|" + password);
        return new MigrateCommand.MigrateOutcome("20", 20);
    };

    private final MigrateCommand.Migrator none = (url, user, password) -> {
        throw new AssertionError("no database may be opened for a refused command");
    };

    @Test
    void it_migrates_as_the_owner_and_reports_the_version() {
        assertThat(run(env(), recording)).isEqualTo(MigrateCommand.OK);
        assertThat(used.get()).isEqualTo("jdbc:postgresql://db:5432/chatledger|chatledger_migrate|owner-password-not-real");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Migrations applied: 20; schema version 20.").doesNotContain("owner-password");
    }

    @Test
    void the_password_comes_from_a_file_and_a_world_readable_one_is_refused() throws Exception {
        Path f = dir.resolve("migrate_password");
        Files.writeString(f, "from-a-file\n");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("r--r-----"));
        Map<String, String> env = env();
        env.remove("MIGRATE_PASSWORD");
        env.put("MIGRATE_PASSWORD_FILE", f.toString());
        assertThat(run(env, recording)).isEqualTo(MigrateCommand.OK);
        assertThat(used.get()).endsWith("|from-a-file");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("r--r--r--"));
        assertThat(run(env, none)).isEqualTo(MigrateCommand.REFUSED);
        env.put("MIGRATE_PASSWORD", "both");
        assertThat(run(env, none)).isEqualTo(MigrateCommand.REFUSED);
        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain("from-a-file").doesNotContain("both\n");
    }

    @Test
    void it_refuses_options_missing_values_placeholders_other_databases_and_the_application_login() {
        assertThat(run(env(), none, "db-migrate", "--force")).isEqualTo(MigrateCommand.USAGE);
        assertThat(run(env(), none, "db-migrates")).isEqualTo(MigrateCommand.USAGE);
        for (String name : new String[]{"DB_URL", "MIGRATE_USER", "MIGRATE_PASSWORD"}) {
            Map<String, String> missing = env();
            missing.remove(name);
            assertThat(run(missing, none)).as(name).isEqualTo(MigrateCommand.USAGE);
            Map<String, String> placeholder = env();
            placeholder.put(name, "<replace-me>");
            assertThat(run(placeholder, none)).as(name).isEqualTo(MigrateCommand.REFUSED);
        }
        for (String name : new String[]{"DB_URL", "MIGRATE_USER", "MIGRATE_PASSWORD"}) {
            Map<String, String> blank = env();
            blank.put(name, "   ");
            assertThat(run(blank, none)).as(name + " blank").isEqualTo(MigrateCommand.USAGE);
        }
        Map<String, String> mysql = env();
        mysql.put("DB_URL", "jdbc:mysql://db/x");
        assertThat(run(mysql, none)).isEqualTo(MigrateCommand.USAGE);
        Map<String, String> sameLogin = env();
        sameLogin.put("MIGRATE_USER", "chatledger_app_login");
        assertThat(run(sameLogin, none)).isEqualTo(MigrateCommand.REFUSED);
    }

    @Test
    void a_failure_names_its_class_only() {
        int code = run(env(), (url, user, password) -> {
            throw new IllegalStateException("ERROR: Failing row contains (owner-password-not-real)");
        });
        assertThat(code).isEqualTo(MigrateCommand.INFRASTRUCTURE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("IllegalStateException").doesNotContain("Failing row").doesNotContain("owner-password");
    }
}
