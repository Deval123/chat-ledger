package dev.chatledger.demo.ops;

import dev.chatledger.demo.web.DemoProperties;
import dev.chatledger.security.LoginEntryPoint;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The demo operator's commands refuse what they must before touching a database: unknown commands and options, a password-like option,
 * a pool or a period out of bounds, a missing or placeholder variable, a URL that is not PostgreSQL, the application's login used as the
 * operator's. The demo settings are bounded. No database: a connection would fail this test.
 *
 * <p>Task 9, D6, D24.
 */
class DemoCommandTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private static Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("DB_URL", "jdbc:postgresql://localhost:5432/demo");
        env.put("DB_USER", "app_login");
        env.put("DB_PASSWORD", "app-password-not-real");
        env.put("DEMO_DB_USER", "demo_login");
        env.put("DEMO_DB_PASSWORD", "demo-password-not-real");
        env.put("APP_SECRET", "x".repeat(40));
        return env;
    }

    private int run(Map<String, String> env, String... args) {
        DemoCommand.Databases none = (url, user, password) -> {
            throw new AssertionError("no database may be opened for a refused command");
        };
        return new DemoCommand(env, none, d -> false, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
    }

    private String errors() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void unknown_commands_and_options_are_refused() {
        assertThat(run(env())).isEqualTo(DemoCommand.USAGE);
        assertThat(run(env(), "demo-reset")).isEqualTo(DemoCommand.USAGE);
        assertThat(run(env(), "demo-init", "--pool", "30")).as("demo-init has no options").isEqualTo(DemoCommand.USAGE);
        assertThat(run(env(), "demo-refresh", "--password", "x")).isEqualTo(DemoCommand.USAGE);
        assertThat(run(env(), "demo-refresh", "pool")).isEqualTo(DemoCommand.USAGE);
        assertThat(run(env(), "demo-refresh", "--pool")).isEqualTo(DemoCommand.USAGE);
        assertThat(run(env(), "demo-refresh", "--pool", "30", "--pool=31")).isEqualTo(DemoCommand.USAGE);
        assertThat(errors()).doesNotContain("x\n").doesNotContain("app-password").doesNotContain("demo-password");
    }

    @Test
    void the_pool_and_the_period_are_bounded() {
        assertThat(run(env(), "demo-refresh", "--pool", "9")).isEqualTo(DemoCommand.USAGE);
        assertThat(run(env(), "demo-refresh", "--pool", "51")).isEqualTo(DemoCommand.USAGE);
        assertThat(run(env(), "demo-refresh", "--pool", "thirty")).isEqualTo(DemoCommand.USAGE);
        assertThat(run(env(), "demo-refresh", "--every", "59s")).isEqualTo(DemoCommand.USAGE);
        assertThat(run(env(), "demo-refresh", "--every", "3601s")).isEqualTo(DemoCommand.USAGE);
        assertThat(run(env(), "demo-refresh", "--every", "soon")).isEqualTo(DemoCommand.USAGE);
        assertThat(errors()).contains("between 10 and 50").contains("between 60s and 3600s");
    }

    @Test
    void the_environment_must_be_complete_real_and_two_separate_logins() {
        for (String name : new String[]{"DB_URL", "DB_USER", "DB_PASSWORD", "DEMO_DB_USER", "DEMO_DB_PASSWORD", "APP_SECRET"}) {
            Map<String, String> missing = env();
            missing.remove(name);
            assertThat(run(missing, "demo-refresh")).as(name).isEqualTo(DemoCommand.USAGE);
            Map<String, String> placeholder = env();
            placeholder.put(name, "<replace-me>");
            assertThat(run(placeholder, "demo-refresh")).as(name + " placeholder").isEqualTo(DemoCommand.REFUSED);
        }
        Map<String, String> notPostgres = env();
        notPostgres.put("DB_URL", "jdbc:mysql://localhost/demo");
        assertThat(run(notPostgres, "demo-init")).isEqualTo(DemoCommand.USAGE);
        Map<String, String> sameLogin = env();
        sameLogin.put("DEMO_DB_USER", "app_login");
        assertThat(run(sameLogin, "demo-init")).isEqualTo(DemoCommand.REFUSED);
        assertThat(errors()).contains("demo operator's own login");
    }

    @Test
    void a_secret_given_both_ways_or_in_a_world_readable_file_is_refused() throws java.io.IOException {
        java.nio.file.Path f = java.nio.file.Files.createTempFile("demo", ".secret");
        try {
            java.nio.file.Files.writeString(f, "file-value-not-real\n");
            java.nio.file.Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--"));
            Map<String, String> everyone = env();
            everyone.remove("DEMO_DB_PASSWORD");
            everyone.put("DEMO_DB_PASSWORD_FILE", f.toString());
            assertThat(run(everyone, "demo-init")).isEqualTo(DemoCommand.REFUSED);
            Map<String, String> both = env();
            both.put("APP_SECRET_FILE", f.toString());
            assertThat(run(both, "demo-init")).isEqualTo(DemoCommand.REFUSED);
            assertThat(errors()).contains("both APP_SECRET and APP_SECRET_FILE").doesNotContain("file-value-not-real");
        } finally {
            java.nio.file.Files.deleteIfExists(f);
        }
    }

    @Test
    void every_secret_can_come_from_its_own_file_and_each_login_gets_its_own_value() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("demo-secrets");
        try {
            Map<String, String> env = env();
            for (String name : new String[]{"DB_PASSWORD", "DEMO_DB_PASSWORD", "APP_SECRET"}) {
                java.nio.file.Path f = dir.resolve(name);
                java.nio.file.Files.writeString(f, (name.equals("APP_SECRET") ? "s".repeat(40) : "from-file-" + name) + "\n");
                java.nio.file.Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("r--r-----"));
                env.remove(name);
                env.put(name + "_FILE", f.toString());
            }
            java.util.List<String> opened = new java.util.ArrayList<>();
            DemoCommand.Databases recording = (url, user, password) -> {
                opened.add(user + "|" + password);
                return (DataSource) java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                        (proxy, method, args) -> {
                            throw new java.sql.SQLException("no database in this test");
                        });
            };
            int code = new DemoCommand(env, recording, d -> false, new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8)).run(new String[]{"demo-refresh"});
            assertThat(code).as(errors()).isNotEqualTo(DemoCommand.USAGE).isNotEqualTo(DemoCommand.REFUSED);
            assertThat(opened).containsExactly("demo_login|from-file-DEMO_DB_PASSWORD", "app_login|from-file-DB_PASSWORD");
            assertThat(errors() + out.toString(StandardCharsets.UTF_8)).doesNotContain("from-file-");
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
                for (java.nio.file.Path f : files.toList()) {
                    java.nio.file.Files.delete(f);
                }
            }
            java.nio.file.Files.delete(dir);
        }
    }

    @Test
    void a_weak_application_secret_is_refused_before_any_seed() {
        Map<String, String> weak = env();
        weak.put("APP_SECRET", "short");
        DataSource unused = new org.postgresql.ds.PGSimpleDataSource();
        int code = new DemoCommand(weak, (url, user, password) -> unused, d -> false,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8)).run(new String[]{"demo-refresh"});
        assertThat(code).isEqualTo(DemoCommand.REFUSED);
        assertThat(errors()).contains("APP_SECRET is not usable").doesNotContain("short");
    }

    @Test
    void the_refresh_itself_refuses_a_pool_out_of_bounds_before_any_database() {
        DemoRefresh refresh = new DemoRefresh(null, null, null);
        assertThatThrownBy(() -> refresh.run(9)).hasMessageContaining("between 10 and 50");
        assertThatThrownBy(() -> refresh.run(51)).hasMessageContaining("between 10 and 50");
    }

    @Test
    void the_catalog_json_holds_the_sixteen_rows_and_escapes_quotes() {
        assertThat(DemoRefresh.catalogJson()).startsWith("[{\"name_key\":\"rice 5kg\"").contains("\"boîte de tomates\"").endsWith("}]");
        assertThat(DemoRefresh.catalogJson().split("\\},\\{")).hasSize(16);
        assertThat(DemoRefresh.json("a\"b\\c\n")).isEqualTo("\"a\\\"b\\\\c\\u000a\"");
    }

    @Test
    void the_demo_settings_are_bounded() {
        // The switch itself is off by default: DemoSwitchedOffIT.
        DemoProperties defaults = new DemoProperties(Duration.ofMinutes(30), "30/10m", "5/1h", "60/1h", "20/10m");
        assertThat(defaults.lease()).isEqualTo(Duration.ofMinutes(30));
        assertThatThrownBy(() -> new DemoProperties(Duration.ofMinutes(9), "30/10m", "5/1h", "60/1h", "20/10m"))
                .hasMessageContaining("app.demo.lease");
        assertThatThrownBy(() -> new DemoProperties(Duration.ofHours(4).plusSeconds(1), "30/10m", "5/1h", "60/1h", "20/10m"))
                .hasMessageContaining("app.demo.lease");
        assertThatThrownBy(() -> new DemoProperties(Duration.ofMinutes(30), "30/10m", "five", "60/1h", "20/10m"))
                .hasMessageContaining("claim-per-address");
        assertThat(new DemoProperties(DemoProperties.MIN_LEASE, "1/1m", "1/1m", "1/1m", "1/1m").lease()).isEqualTo(Duration.ofMinutes(10));
        assertThat(new DemoProperties(DemoProperties.MAX_LEASE, "1/1m", "1/1m", "1/1m", "1/1m").lease()).isEqualTo(Duration.ofHours(4));
    }

    @Test
    void the_entry_point_is_the_sign_in_page_or_the_demo_page_and_nothing_else() {
        new LoginEntryPoint("/login");
        new LoginEntryPoint("/demo");
        assertThatThrownBy(() -> new LoginEntryPoint("https://elsewhere.example")).isInstanceOf(IllegalArgumentException.class);
    }
}
