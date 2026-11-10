package dev.chatledger.provisioning;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provisioning command's rules that need no database: it refuses to take a password from
 * the command line or the environment, validates every input, and never prints a secret.
 *
 * <p>Requirement: 9.4. Task 3.6.
 */
class ProvisioningCommandTest {

    private static final String GOOD_PASSWORD = "correct-" + UUID.randomUUID();

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger passwordReads = new AtomicInteger();
    private final Map<String, String> env = new HashMap<>();

    private ProvisioningCommand command(String password) {
        env.put("PROVISION_DB_URL", "jdbc:postgresql://localhost:1/none");
        env.put("PROVISION_DB_USER", "provisioner");
        env.put("PROVISION_DB_PASSWORD", UUID.randomUUID().toString());
        return new ProvisioningCommand(env,
                confirm -> {
                    passwordReads.incrementAndGet();
                    return Optional.ofNullable(password);
                },
                (url, user, pass) -> {
                    connections.incrementAndGet();
                    throw new SQLException("boom: Failing row contains (" + GOOD_PASSWORD + ")", "08001");
                },
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return outBytes.toString(StandardCharsets.UTF_8) + errBytes.toString(StandardCharsets.UTF_8);
    }

    private static String[] tenantArgs(String... extra) {
        String[] base = {"provision-tenant", "--workspace", "river-shop", "--name", "River Shop",
                "--email", "seller@example.test"};
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    @Test
    void a_password_option_is_refused_and_the_value_is_not_echoed() {
        for (String flag : new String[]{"--password", "--password=" + GOOD_PASSWORD, "--pwd", "--password-hash",
                "--hash", "--user-secret", "--passphrase"}) {
            String[] args = flag.contains("=") ? tenantArgs(flag) : tenantArgs(flag, GOOD_PASSWORD);
            assertThat(command(GOOD_PASSWORD).run(args)).as(flag).isEqualTo(ProvisioningCommand.REFUSED);
            assertThat(output()).as(flag).doesNotContain(GOOD_PASSWORD);
        }
        assertThat(connections.get()).isZero();
        assertThat(passwordReads.get()).isZero();
    }

    @Test
    void a_password_or_hash_in_the_environment_is_refused() {
        for (String name : new String[]{"PROVISION_PASSWORD", "PROVISION_USER_PASSWORD", "PROVISION_PASSWORD_HASH",
                "PROVISION_HASH", "PROVISION_PWD", "PROVISION_SECRET"}) {
            ProvisioningCommand c = command(GOOD_PASSWORD);
            env.put(name, GOOD_PASSWORD);
            assertThat(c.run(tenantArgs())).as(name).isEqualTo(ProvisioningCommand.REFUSED);
        }
        assertThat(output()).doesNotContain(GOOD_PASSWORD);
        assertThat(connections.get()).isZero();
    }

    @Test
    void the_database_login_password_variable_is_allowed() {
        assertThat(ProvisioningCommand.forbiddenEnvironmentName("PROVISION_DB_PASSWORD")).isFalse();
        assertThat(ProvisioningCommand.forbiddenEnvironmentName("PROVISION_DB_USER")).isFalse();
    }

    @Test
    void placeholders_from_env_example_are_refused() {
        ProvisioningCommand c = command(GOOD_PASSWORD);
        env.put("PROVISION_DB_PASSWORD", "<replace-with-strong-provisioner-password>");
        assertThat(c.run(tenantArgs())).isEqualTo(ProvisioningCommand.REFUSED);
        assertThat(connections.get()).isZero();
    }

    @Test
    void invalid_inputs_are_rejected_before_the_password_is_read_or_the_database_is_touched() {
        String[][] bad = {
                {"provision-tenant", "--workspace", "A", "--name", "x", "--email", "seller@example.test"},
                {"provision-tenant", "--workspace", "river shop", "--name", "x", "--email", "seller@example.test"},
                {"provision-tenant", "--workspace", "river-shop", "--name", "", "--email", "seller@example.test"},
                {"provision-tenant", "--workspace", "river-shop", "--name", "x\ny", "--email", "seller@example.test"},
                {"provision-tenant", "--workspace", "river-shop", "--name", "x", "--email", "not-an-email"},
                {"provision-tenant", "--workspace", "river-shop", "--name", "x"},
                {"provision-tenant", "--workspace", "river-shop", "--name", "x", "--email", "a@b.test", "--role", "SELLER"},
                {"provision-tenant", "--workspace", "river-shop", "--name", "x", "--name", "y", "--email", "a@b.test"},
                {"provision-user", "--workspace", "river-shop", "--email", "a@b.test", "--role", "ADMIN"},
                {"provision-user", "--workspace", "river-shop", "--email", "a@b.test"},
                {"provision-tenant", "stray"},
                {"provision-other"},
                {},
        };
        for (String[] args : bad) {
            assertThat(command(GOOD_PASSWORD).run(args)).as(String.join(" ", args))
                    .isEqualTo(ProvisioningCommand.USAGE);
        }
        assertThat(connections.get()).isZero();
        assertThat(passwordReads.get()).isZero();
    }

    @Test
    void a_weak_or_missing_password_is_rejected_and_never_printed() {
        String weak = "short";
        assertThat(command(weak).run(tenantArgs())).isEqualTo(ProvisioningCommand.USAGE);
        assertThat(output()).doesNotContain(weak + "\n");
        assertThat(command(null).run(tenantArgs())).isEqualTo(ProvisioningCommand.USAGE);
        // Built from the email's local part.
        assertThat(command("seller-seller-seller").run(tenantArgs())).isEqualTo(ProvisioningCommand.USAGE);
        assertThat(connections.get()).isZero();
    }

    @Test
    void a_database_failure_is_reported_by_sqlstate_only() {
        int code = command(GOOD_PASSWORD).run(tenantArgs());
        assertThat(code).isEqualTo(ProvisioningCommand.INFRASTRUCTURE);
        assertThat(connections.get()).isEqualTo(1);
        assertThat(output()).contains("08001").doesNotContain(GOOD_PASSWORD).doesNotContain("Failing row")
                .doesNotContain("argon2");
    }

    @Test
    void missing_database_variables_are_a_usage_error() {
        ProvisioningCommand c = command(GOOD_PASSWORD);
        env.remove("PROVISION_DB_URL");
        assertThat(c.run(tenantArgs())).isEqualTo(ProvisioningCommand.USAGE);
        env.put("PROVISION_DB_URL", "jdbc:mysql://x/y");
        assertThat(c.run(tenantArgs())).isEqualTo(ProvisioningCommand.USAGE);
        assertThat(connections.get()).isZero();
    }

    @Test
    void the_application_main_dispatches_provisioning_without_a_spring_context() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/dev/chatledger/web/ChatLedgerApplication.java"));
        assertThat(source.indexOf("ProvisioningCommand.main")).isGreaterThan(0)
                .isLessThan(source.indexOf("SpringApplication.run"));
    }

    @Test
    void without_a_terminal_the_password_is_one_line_of_standard_input() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(ProvisioningCommand.interactiveConsole() == null, "a terminal is attached");
        ProvisioningCommand.PasswordSource source = ProvisioningCommand.consoleOrStdin(
                new java.io.ByteArrayInputStream(("first line " + GOOD_PASSWORD + "\nsecond line\n").getBytes(StandardCharsets.UTF_8)));
        assertThat(source.read(true)).contains("first line " + GOOD_PASSWORD);
        assertThat(ProvisioningCommand.consoleOrStdin(new java.io.ByteArrayInputStream(new byte[0])).read(true)).isEmpty();
        assertThat(ProvisioningCommand.consoleOrStdin(new java.io.ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8))).read(true)).isEmpty();
    }

    /** V020 (task 9): a demo deployment refuses real workspaces; the command says so and exits as refused, not as a failure. */
    @Test
    void a_demo_deployment_refuses_provisioning_with_its_own_sentence() {
        env.put("PROVISION_DB_URL", "jdbc:postgresql://localhost:1/none");
        env.put("PROVISION_DB_USER", "provisioner");
        env.put("PROVISION_DB_PASSWORD", UUID.randomUUID().toString());
        ProvisioningCommand demo = new ProvisioningCommand(env, confirm -> Optional.of(GOOD_PASSWORD),
                (url, user, pass) -> { throw new SQLException("workspaces cannot be provisioned on a demo deployment", "55000"); },
                new PrintStream(outBytes, true, StandardCharsets.UTF_8), new PrintStream(errBytes, true, StandardCharsets.UTF_8));
        assertThat(demo.run(tenantArgs())).isEqualTo(ProvisioningCommand.REFUSED);
        assertThat(output()).contains("this is a demo deployment").doesNotContain(GOOD_PASSWORD);
    }
}
