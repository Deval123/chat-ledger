package dev.chatledger.deploy;

import dev.chatledger.ledger.PostgresRelease;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code deploy/compose.yaml}, the stack on the shared VPS, holds the design's isolation and hardening (task 13, section 2 and D3 to
 * D7): no published port, no privilege, no socket, no shared network, a memory limit without swap on every service, read-only roots,
 * no secret in an environment variable, each login only where it is used, every image pinned by digest. The two companion files
 * (the hops echo and the CI override) cannot weaken any of it. No Docker needed: the files are read as YAML.
 */
class ComposeFileTest {

    static final Path DEPLOY = Path.of("..", "deploy");
    private static final Pattern DIGEST = Pattern.compile("@sha256:[0-9a-f]{64}$");
    private static final String RELEASE = "ghcr.io/deval123/chat-ledger@${CHAT_LEDGER_DIGEST:?";
    private static final Set<String> APP_SERVICES = Set.of("migrate", "web", "refresh");

    @SuppressWarnings("unchecked")
    static Map<String, Object> yaml(String file) throws IOException {
        return (Map<String, Object>) new Yaml().load(Files.readString(DEPLOY.resolve(file)));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o == null ? Map.of() : (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o == null ? List.of() : (List<Object>) o;
    }

    private static Map<String, Object> services() throws IOException {
        return map(yaml("compose.yaml").get("services"));
    }

    private static Map<String, Object> service(String name) throws IOException {
        return map(services().get(name));
    }

    /** Megabytes of a compose size such as "640m". */
    private static int mib(Object size) {
        String s = String.valueOf(size);
        assertThat(s).as("a size in m").matches("[0-9]+m");
        return Integer.parseInt(s.substring(0, s.length() - 1));
    }

    @Test
    void the_project_has_a_fixed_name_and_exactly_the_five_services() throws IOException {
        assertThat(yaml("compose.yaml").get("name")).isEqualTo("chat-ledger");
        assertThat(services().keySet()).containsExactlyInAnyOrder("db", "migrate", "web", "refresh", "tunnel");
        assertThat(list(service("migrate").get("profiles"))).as("migrate is one-shot, never started by up").containsExactly("ops");
    }

    @Test
    void every_service_is_hardened_and_bounded() throws IOException {
        for (Map.Entry<String, Object> e : services().entrySet()) {
            String name = e.getKey();
            Map<String, Object> s = map(e.getValue());
            assertThat(s.get("read_only")).as(name + " read_only").isEqualTo(true);
            assertThat(list(s.get("cap_drop"))).as(name + " cap_drop").containsExactly("ALL");
            assertThat(list(s.get("security_opt"))).as(name + " security_opt").containsExactly("no-new-privileges:true");
            assertThat((Integer) s.get("pids_limit")).as(name + " pids_limit").isBetween(1, 200);
            assertThat(s.get("mem_limit")).as(name + " mem_limit").isNotNull();
            assertThat(s.get("memswap_limit")).as(name + ": no swap").isEqualTo(s.get("mem_limit"));
            assertThat(((Number) s.get("cpus")).doubleValue()).as(name + " cpus").isBetween(0.1, 1.0);
            assertThat(s.get("restart")).as(name + " restart").isEqualTo(name.equals("migrate") ? "no" : "unless-stopped");
            Map<String, Object> logging = map(s.get("logging"));
            assertThat(logging.get("driver")).as(name + " logging").isEqualTo("json-file");
            assertThat(map(logging.get("options"))).as(name + " log rotation").containsEntry("max-size", "10m").containsEntry("max-file", "3");
        }
    }

    @Test
    void no_service_publishes_a_port_or_reaches_the_host() throws IOException {
        Set<String> forbidden = Set.of("ports", "privileged", "network_mode", "pid", "ipc", "uts", "userns_mode", "cgroup", "extra_hosts",
                "devices", "cap_add", "device_cgroup_rules", "sysctls", "volumes_from", "env_file", "build");
        for (Map.Entry<String, Object> e : services().entrySet()) {
            Map<String, Object> s = map(e.getValue());
            assertThat(s.keySet()).as(e.getKey()).doesNotContainAnyElementsOf(forbidden);
            for (Object volume : list(s.get("volumes"))) {
                String v = String.valueOf(volume);
                assertThat(v).as(e.getKey() + " volume").doesNotContain("docker.sock").doesNotStartWith("/");
                assertThat(v).as(e.getKey() + " volume").isIn("db-data:/var/lib/postgresql/data", "./initdb:/docker-entrypoint-initdb.d:ro");
            }
        }
        assertThat(list(service("db").get("volumes"))).hasSize(2);
        assertThat(map(yaml("compose.yaml").get("volumes")).keySet()).containsExactly("db-data");
    }

    @Test
    void three_networks_of_its_own_and_only_the_tunnel_has_a_route_out() throws IOException {
        Map<String, Object> networks = map(yaml("compose.yaml").get("networks"));
        assertThat(networks.keySet()).containsExactlyInAnyOrder("internal", "edge", "egress");
        assertThat(map(networks.get("internal")).get("internal")).isEqualTo(true);
        assertThat(map(networks.get("edge")).get("internal")).isEqualTo(true);
        for (Object network : networks.values()) {
            assertThat(map(network).keySet()).as("never external, never a shared name").doesNotContain("external", "name");
        }
        assertThat(list(service("db").get("networks"))).containsExactly("internal");
        assertThat(list(service("migrate").get("networks"))).containsExactly("internal");
        assertThat(list(service("refresh").get("networks"))).containsExactly("internal");
        assertThat(list(service("web").get("networks"))).containsExactlyInAnyOrder("internal", "edge");
        assertThat(list(service("tunnel").get("networks"))).containsExactlyInAnyOrder("edge", "egress");
        Map<String, Object> egress = map(networks.get("egress"));
        assertThat(egress.get("internal")).as("egress is the one normal bridge").isNull();
        assertThat(String.valueOf(map(egress.get("ipam")).get("config"))).as("a fixed subnet for the D17 rule").contains("subnet=10.213.7.0/28");
    }

    @Test
    void every_image_is_pinned_and_the_database_is_the_one_the_tests_use() throws IOException {
        for (Map.Entry<String, Object> e : services().entrySet()) {
            String image = String.valueOf(map(e.getValue()).get("image"));
            if (APP_SERVICES.contains(e.getKey())) {
                assertThat(image).as(e.getKey() + ": the release, by digest only").startsWith(RELEASE);
            } else {
                assertThat(image).as(e.getKey()).containsPattern(DIGEST);
            }
        }
        assertThat(service("db").get("image")).as("D9").isEqualTo(PostgresRelease.IMAGE);
        assertThat(String.valueOf(service("tunnel").get("image"))).startsWith("cloudflare/cloudflared:2026.10.7@sha256:");
    }

    @Test
    void no_secret_is_in_an_environment_variable_and_each_login_is_only_where_it_is_used() throws IOException {
        Pattern secretName = Pattern.compile(".*(PASSWORD|SECRET|TOKEN|KEY).*");
        for (Map.Entry<String, Object> e : services().entrySet()) {
            Map<String, Object> env = map(map(e.getValue()).get("environment"));
            for (Map.Entry<String, Object> v : env.entrySet()) {
                if (secretName.matcher(v.getKey()).matches()) {
                    assertThat(v.getKey()).as(e.getKey() + ": a secret only as a file").endsWith("_FILE");
                    assertThat(String.valueOf(v.getValue())).as(e.getKey() + " " + v.getKey()).startsWith("/run/secrets/");
                }
                assertThat(v.getKey()).as(e.getKey()).isNotEqualTo("JAVA_TOOL_OPTIONS").isNotEqualTo("TUNNEL_TOKEN");
            }
        }
        Map<String, Object> web = map(service("web").get("environment"));
        assertThat(web.keySet()).as("web never holds the demo operator or the migrate login")
                .noneMatch(k -> k.startsWith("DEMO_DB_") || k.startsWith("MIGRATE_") || k.startsWith("PROVISION_") || k.startsWith("POSTGRES_"));
        assertThat(web).containsEntry("SPRING_PROFILES_ACTIVE", "prod");
        assertThat(map(service("refresh").get("environment")).keySet()).noneMatch(k -> k.startsWith("MIGRATE_") || k.startsWith("PROVISION_"));
        assertThat(map(service("migrate").get("environment")).keySet()).noneMatch(k -> k.startsWith("DEMO_DB_") || k.startsWith("PROVISION_"));
        assertThat(map(service("tunnel").get("environment"))).isEmpty();

        assertThat(secretTargets("web")).containsExactlyInAnyOrder("spring.datasource.password", "app.secret");
        assertThat(secretTargets("refresh")).containsExactlyInAnyOrder("app_password", "demo_operator_password", "app_secret");
        assertThat(secretTargets("migrate")).containsExactly("migrate_password");
        assertThat(secretTargets("tunnel")).containsExactly("tunnel_token");
        assertThat(secretTargets("db")).containsExactlyInAnyOrder("pg_superuser_password", "migrate_password", "app_password",
                "provisioner_password", "demo_operator_password");
    }

    @Test
    void each_secret_file_is_in_the_directory_of_the_one_uid_that_reads_it() throws IOException {
        Map<String, Object> secrets = map(yaml("compose.yaml").get("secrets"));
        for (Map.Entry<String, Object> e : secrets.entrySet()) {
            String file = String.valueOf(map(e.getValue()).get("file"));
            assertThat(map(e.getValue()).keySet()).as(e.getKey()).containsExactly("file");
            if (e.getKey().startsWith("db_")) {
                assertThat(file).as(e.getKey()).isEqualTo("./secrets/db/" + e.getKey().substring(3));
            } else {
                assertThat(e.getKey()).startsWith("app_");
                assertThat(file).as(e.getKey()).isEqualTo("./secrets/app/" + e.getKey().substring(4));
            }
        }
        for (String name : services().keySet()) {
            String prefix = name.equals("db") ? "db_" : "app_";
            for (Object s : list(service(name).get("secrets"))) {
                assertThat(String.valueOf(map(s).get("source"))).as(name + ": uid " + (name.equals("db") ? 70 : 65532)).startsWith(prefix);
            }
        }
        assertThat(service("db").get("user")).isEqualTo("70:70");
        assertThat(service("tunnel").get("user")).isEqualTo("65532:65532");
    }

    private static Set<String> secretTargets(String service) throws IOException {
        Set<String> targets = new TreeSet<>();
        for (Object s : list(service(service).get("secrets"))) {
            targets.add(String.valueOf(map(s).get("target")));
        }
        return targets;
    }

    @Test
    void the_memory_budget_is_the_design_s() throws IOException {
        assertThat(mib(service("web").get("mem_limit"))).isEqualTo(640);
        assertThat(mib(service("refresh").get("mem_limit"))).as("raised from 192 after the amd64 seed was killed").isEqualTo(256);
        assertThat(mib(service("db").get("mem_limit"))).isEqualTo(384);
        assertThat(mib(service("tunnel").get("mem_limit"))).isEqualTo(96);
        assertThat(mib(service("migrate").get("mem_limit"))).isEqualTo(256);
        int running = 0;
        for (String s : List.of("web", "refresh", "db", "tunnel")) {
            running += mib(service(s).get("mem_limit"));
        }
        assertThat(running).as("D7 as amended: at most 1,376 MiB while serving").isLessThanOrEqualTo(1376);
        assertThat(list(service("web").get("entrypoint"))).contains("-Xmx288m", "-XX:MaxMetaspaceSize=160m", "-XX:ReservedCodeCacheSize=48m",
                "-XX:MaxDirectMemorySize=32m", "-XX:+ExitOnOutOfMemoryError");
        assertThat(list(service("refresh").get("entrypoint"))).as("the heap stays at 96m").contains("-Xmx96m", "-XX:+ExitOnOutOfMemoryError");
        assertThat(String.valueOf(map(service("web").get("healthcheck")).get("test"))).contains("health-probe", "-Xmx16m");
    }

    @Test
    void postgres_logs_no_statement_and_no_value() throws IOException {
        List<Object> command = list(service("db").get("command"));
        assertThat(command).contains("log_error_verbosity=terse", "log_statement=none", "log_min_duration_statement=-1",
                "log_min_error_statement=panic", "log_parameter_max_length=0", "log_parameter_max_length_on_error=0",
                "log_connections=off", "log_disconnections=off", "max_connections=30");
    }

    @Test
    void the_tunnel_reads_its_token_from_a_file_and_keeps_its_metrics_local() throws IOException {
        List<Object> command = list(service("tunnel").get("command"));
        assertThat(command).containsSubsequence("--token-file", "/run/secrets/tunnel_token");
        assertThat(command).containsSubsequence("--metrics", "127.0.0.1:20241");
        assertThat(command).contains("--no-autoupdate", "--management-diagnostics=false");
        assertThat(command).noneMatch(a -> String.valueOf(a).startsWith("--token="));
    }

    @Test
    void the_web_process_runs_the_demo_without_flyway_s_login() throws IOException {
        assertThat(list(service("web").get("command"))).containsExactly("--app.demo.enabled=true");
        assertThat(list(service("migrate").get("command"))).containsExactly("db-migrate");
        assertThat(list(service("refresh").get("command"))).startsWith("demo-refresh");
    }

    @Test
    void the_hops_echo_is_on_the_edge_only_and_as_hardened() throws IOException {
        Map<String, Object> services = map(yaml("hops-echo.yaml").get("services"));
        assertThat(services.keySet()).containsExactly("hops-echo");
        Map<String, Object> echo = map(services.get("hops-echo"));
        assertThat(String.valueOf(echo.get("image"))).containsPattern(DIGEST);
        assertThat(list(echo.get("networks"))).containsExactly("edge");
        assertThat(echo.get("read_only")).isEqualTo(true);
        assertThat(list(echo.get("cap_drop"))).containsExactly("ALL");
        assertThat(list(echo.get("security_opt"))).containsExactly("no-new-privileges:true");
        assertThat(echo.get("memswap_limit")).isEqualTo(echo.get("mem_limit"));
        assertThat(echo.get("restart")).as("time-boxed: never restarted (register 157)").isEqualTo("no");
        assertThat(echo.get("user")).isEqualTo("65532:65532");
        assertThat(list(echo.get("command"))).as("not on a privileged port").containsExactly("--port", "8080");
        assertThat(echo.keySet()).doesNotContain("ports", "privileged", "volumes", "network_mode", "secrets", "environment");
        assertThat(yaml("hops-echo.yaml").keySet()).containsExactly("services");
    }

    @Test
    void the_ci_override_only_swaps_the_image() throws IOException {
        Map<String, Object> test = yaml("compose.test.yaml");
        assertThat(test.keySet()).containsExactly("services");
        Map<String, Object> services = map(test.get("services"));
        assertThat(services.keySet()).containsExactlyInAnyOrderElementsOf(APP_SERVICES);
        for (Object s : services.values()) {
            assertThat(map(s)).containsOnlyKeys("image", "pull_policy").containsEntry("image", "chat-ledger:test").containsEntry("pull_policy", "never");
        }
    }

    @Test
    void the_example_settings_hold_no_secret_and_start_with_hops_at_zero() throws IOException {
        String example = Files.readString(DEPLOY.resolve("env.example"));
        assertThat(example).contains("APP_SECURITY_TRUSTED_PROXY_HOPS=0\n").contains("CHAT_LEDGER_DIGEST=sha256:<");
        assertThat(example.lines().filter(l -> !l.startsWith("#") && !l.isBlank()).map(l -> l.substring(0, l.indexOf('='))))
                .containsExactly("CHAT_LEDGER_DIGEST", "APP_SECURITY_TRUSTED_PROXY_HOPS", "DEMO_POOL");
    }
}
