package dev.chatledger.provisioning;

import dev.chatledger.ledger.LedgerDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import dev.chatledger.security.PasswordHashing;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provisioning against a real PostgreSQL: the command creates a workspace and users through
 * the provisioner login, the stored value is an Argon2id hash that verifies, and the
 * privileges are as narrow as the design says.
 *
 * <p>Requirement: 9.1, 9.4. Task 3.6.
 */
class ProvisioningIT {

    private static final LedgerDatabase DB = LedgerDatabase.get();
    private static final PasswordEncoder ENCODER = PasswordHashing.create();

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int run(String password, String... args) {
        Map<String, String> env = Map.of(
                "PROVISION_DB_URL", DB.jdbcUrl(),
                "PROVISION_DB_USER", LedgerDatabase.PROVISION_USER,
                "PROVISION_DB_PASSWORD", DB.provisionerPassword());
        return new ProvisioningCommand(env, confirm -> Optional.ofNullable(password),
                java.sql.DriverManager::getConnection,
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8)).run(args);
    }

    private static String code() {
        return "shop-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String password() {
        return "pw-" + UUID.randomUUID() + "-x";
    }

    private record Row(String tenantSlug, String role, String hash, String tenantId) { }

    private Row user(String email) throws SQLException {
        try (Connection c = DB.migrateDataSource().getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT t.slug, u.role, u.password_hash, t.id::text FROM app_user u "
                     + "JOIN tenant t ON t.id = u.tenant_id WHERE u.email = ?")) {
            s.setString(1, email);
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).as("user " + email).isTrue();
                return new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
            }
        }
    }

    @Test
    void a_workspace_and_its_seller_are_created_with_an_argon2id_hash() throws SQLException {
        String slug = code();
        String password = password();
        String email = "seller-" + slug + "@example.test";
        assertThat(run(password, "provision-tenant", "--workspace", slug, "--name", "River Shop",
                "--email", email)).isEqualTo(ProvisioningCommand.OK);

        Row row = user(email);
        assertThat(row.tenantSlug()).isEqualTo(slug);
        assertThat(row.role()).isEqualTo("SELLER");
        assertThat(row.hash()).startsWith("{argon2id}$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain(password);
        assertThat(ENCODER.matches(password, row.hash())).isTrue();
        assertThat(ENCODER.matches(password + "x", row.hash())).isFalse();
        assertThat(outBytes.toString(StandardCharsets.UTF_8) + errBytes.toString(StandardCharsets.UTF_8))
                .doesNotContain(password).doesNotContain("argon2");
    }

    @Test
    void a_helper_can_be_added_and_a_duplicate_or_unknown_workspace_is_refused_cleanly() throws SQLException {
        String slug = code();
        String seller = "seller-" + slug + "@example.test";
        String helper = "helper-" + slug + "@example.test";
        assertThat(run(password(), "provision-tenant", "--workspace", slug, "--name", "Shop", "--email", seller))
                .isEqualTo(ProvisioningCommand.OK);
        assertThat(run(password(), "provision-user", "--workspace", slug, "--email", helper, "--role", "helper"))
                .isEqualTo(ProvisioningCommand.OK);
        assertThat(user(helper).role()).isEqualTo("HELPER");
        assertThat(user(helper).tenantId()).isEqualTo(user(seller).tenantId());

        // Same email in the same workspace.
        assertThat(run(password(), "provision-user", "--workspace", slug, "--email", helper, "--role", "HELPER"))
                .isEqualTo(ProvisioningCommand.USAGE);
        // Same workspace code again.
        assertThat(run(password(), "provision-tenant", "--workspace", slug, "--name", "Shop",
                "--email", "other-" + slug + "@example.test")).isEqualTo(ProvisioningCommand.USAGE);
        // Unknown workspace.
        assertThat(run(password(), "provision-user", "--workspace", code(), "--email", helper, "--role", "HELPER"))
                .isEqualTo(ProvisioningCommand.USAGE);
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).doesNotContain("argon2").doesNotContain("Detail");
    }

    @Test
    void the_same_email_may_exist_in_two_workspaces() throws SQLException {
        String a = code();
        String b = code();
        String email = "shared-" + a + "@example.test";
        assertThat(run(password(), "provision-tenant", "--workspace", a, "--name", "A", "--email", email))
                .isEqualTo(ProvisioningCommand.OK);
        assertThat(run(password(), "provision-tenant", "--workspace", b, "--name", "B", "--email", email))
                .isEqualTo(ProvisioningCommand.OK);
    }

    @Test
    void a_failed_provisioning_leaves_nothing_behind() throws SQLException {
        String slug = code();
        // The email passes the command's check but not the table constraint? Use a rejected
        // workspace name at the database instead: 101 characters is stopped by the command, so
        // call the function directly to prove the transaction is all or nothing.
        try (Connection c = DB.provisionerDataSource().getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM provision_tenant(?, ?, ?, ?)")) {
            s.setString(1, slug);
            s.setString(2, "Shop");
            s.setString(3, "nobody@example.test");
            s.setString(4, "{noop}plaintext"); // refused by chk_app_user_password_hash_format
            assertThatThrownBy(s::executeQuery).isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
        }
        try (Connection c = DB.migrateDataSource().getConnection();
             PreparedStatement s = c.prepareStatement("SELECT count(*) FROM tenant WHERE slug = ?")) {
            s.setString(1, slug);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    void the_provisioner_login_can_execute_the_functions_and_do_nothing_else() throws SQLException {
        try (Connection c = DB.provisionerDataSource().getConnection(); Statement s = c.createStatement()) {
            for (String sql : new String[]{
                    "SELECT * FROM tenant", "SELECT * FROM app_user", "SELECT * FROM audit_log",
                    "SELECT * FROM ledger_entry", "INSERT INTO tenant(id,name,currency,scale,slug) "
                    + "VALUES (gen_random_uuid(),'x','XAF',0,'direct-insert')",
                    "SELECT resolve_tenant_for_login('x')"}) {
                assertThatThrownBy(() -> s.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
            }
        }
    }

    @Test
    void the_application_login_cannot_provision_or_write_users() throws SQLException {
        try (Connection c = DB.appDataSource().getConnection(); Statement s = c.createStatement()) {
            for (String sql : new String[]{
                    "SELECT * FROM provision_tenant('app-try','x','x@example.test','{argon2id}$argon2id$v=19$m=1,t=1,p=1$aa$bb')",
                    "SELECT provision_user('app-try','x@example.test','SELLER','{argon2id}$argon2id$v=19$m=1,t=1,p=1$aa$bb')",
                    "UPDATE app_user SET role = 'SELLER'",
                    "INSERT INTO app_user(id,tenant_id,email,password_hash,role) VALUES "
                    + "(gen_random_uuid(),gen_random_uuid(),'a@example.test','x','SELLER')",
                    "DELETE FROM app_user", "UPDATE tenant SET name = 'x'",
                    "INSERT INTO tenant(id,name,currency,scale,slug) VALUES (gen_random_uuid(),'x','XAF',0,'app-insert')"}) {
                assertThatThrownBy(() -> s.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
            }
        }
    }

    @Test
    void execute_on_the_three_definer_functions_is_held_by_exactly_the_intended_roles() throws SQLException {
        Map<String, String> expected = Map.of(
                "provision_tenant", "chatledger_provisioner",
                "provision_user", "chatledger_provisioner",
                "resolve_tenant_for_login", "chatledger_app");
        try (Connection c = DB.migrateDataSource().getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT p.proname, "
                     + "(SELECT string_agg(coalesce(nullif(a.grantee, 0)::regrole::text, 'PUBLIC'), ',' ORDER BY a.grantee) "
                     + "   FROM aclexplode(p.proacl) a WHERE a.privilege_type = 'EXECUTE' AND a.grantee <> p.proowner) "
                     + "FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                     + "WHERE n.nspname = 'public' AND p.prosecdef")) {
            Map<String, String> found = new java.util.HashMap<>();
            while (rs.next()) {
                found.put(rs.getString(1), rs.getString(2));
            }
            assertThat(found).isEqualTo(expected);
        }
    }
}
