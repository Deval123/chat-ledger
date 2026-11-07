package dev.chatledger.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.chatledger.ledger.LedgerDatabase;
import dev.chatledger.web.ChatLedgerApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Base for tests that run the whole application on a real server against real PostgreSQL, connected
 * as the application role exactly as in production. Users are created the way an operator creates them
 * (the provisioning functions, through the provisioner login), never by a back door.
 *
 * <p>Flyway has already run (as the migrate role) in {@link LedgerDatabase}.
 */
@SpringBootTest(classes = ChatLedgerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=false",
                // Every test in a shared context signs in from one address, so the sign-in limits are out of the way
                // here. The limit tests run in their own contexts with the real defaults.
                "app.security.limits.login-per-ip=1000000/1m",
                "app.security.limits.login-per-account=1000000/1m",
                "app.security.limits.login-page-per-ip=1000000/1m"})
@ActiveProfiles("probe")
@Import(AbstractWebIT.TestClockConfig.class)
abstract class AbstractWebIT {

    static final LedgerDatabase DB = LedgerDatabase.get();
    private static final String APP_SECRET = UUID.randomUUID() + "" + UUID.randomUUID();
    private static final PasswordEncoder ENCODER = PasswordHashing.create();

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    record Workspace(String slug, UUID tenantId, String email, UUID sellerId, String password) { }

    record Member(UUID userId, String email, String password) { }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> LedgerDatabase.APP_USER);
        registry.add("spring.datasource.password", DB::appPassword);
        registry.add("spring.flyway.user", () -> UUID.randomUUID().toString());
        registry.add("spring.flyway.password", () -> UUID.randomUUID().toString());
        registry.add("app.secret", () -> APP_SECRET);
    }

    @LocalServerPort
    int port;

    @Autowired
    MutableClock clock;

    @Autowired
    AuditLogRepository audit;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        // Debug output from the framework is where request details would leak, so capture it too.
        root.setLevel(Level.DEBUG);
    }

    @AfterEach
    void releaseLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(logs);
        root.setLevel(Level.INFO);
    }

    /** Everything logged since the test began, as one string (messages and exception texts). */
    String loggedText() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : List.copyOf(logs.list)) {
            sb.append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) {
                sb.append(e.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    static String newPassword() {
        return "pw-" + UUID.randomUUID() + "-x";
    }

    /** A new workspace with a SELLER, created through the provisioning function. */
    Workspace newWorkspace() {
        String slug = "shop-" + unique();
        String email = "seller-" + unique() + "@example.test";
        String password = newPassword();
        try (Connection c = DB.provisionerDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT out_tenant_id, out_user_id FROM provision_tenant(?, ?, ?, ?)")) {
            ps.setString(1, slug);
            ps.setString(2, "Test shop");
            ps.setString(3, email);
            ps.setString(4, ENCODER.encode(password));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Workspace(slug, rs.getObject(1, UUID.class), email, rs.getObject(2, UUID.class), password);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    Member addUser(Workspace w, Role role) {
        String email = role.name().toLowerCase() + "-" + unique() + "@example.test";
        String password = newPassword();
        try (Connection c = DB.provisionerDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT provision_user(?, ?, ?, ?)")) {
            ps.setString(1, w.slug());
            ps.setString(2, email);
            ps.setString(3, role.name());
            ps.setString(4, ENCODER.encode(password));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Member(rs.getObject(1, UUID.class), email, password);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs SQL as the schema owner: the operator-level changes the application itself cannot make. */
    void asOwner(String sql, Object... args) {
        try (Connection c = DB.migrateDataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    Browser browser() {
        return new Browser(port);
    }

    /** Loads the login page, then submits the form the way a browser does. */
    Browser.Response signIn(Browser browser, String workspace, String email, String password) {
        Browser.Response page = browser.get("/login");
        return browser.post("/login", Map.of("_csrf", Browser.csrf(page), "workspace", workspace,
                "email", email, "password", password));
    }

    /** A browser signed in as the given user; fails the test if sign-in does not succeed. */
    Browser signedIn(String workspace, String email, String password) {
        Browser b = browser();
        Browser.Response r = signIn(b, workspace, email, password);
        if (r.status() != 302 || !r.header("location").endsWith("/app")) {
            throw new AssertionError("sign-in failed: " + r.status() + " " + r.header("location"));
        }
        return b;
    }

    /** The content security policy of the design, written out here so the test does not just echo the code. */
    static final String EXPECTED_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; "
            + "font-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'; object-src 'none'; "
            + "frame-ancestors 'none'";

    /** Every response, whatever its status, carries the security headers and names no server. */
    static void assertSecurityHeaders(Browser.Response r, String what) {
        org.assertj.core.api.Assertions.assertThat(r.header("content-security-policy")).as(what + " CSP").isEqualTo(EXPECTED_CSP);
        org.assertj.core.api.Assertions.assertThat(r.header("x-content-type-options")).as(what).isEqualTo("nosniff");
        org.assertj.core.api.Assertions.assertThat(r.header("referrer-policy")).as(what).isEqualTo("no-referrer");
        org.assertj.core.api.Assertions.assertThat(r.header("x-frame-options")).as(what).isEqualTo("DENY");
        org.assertj.core.api.Assertions.assertThat(r.header("cross-origin-opener-policy")).as(what).isEqualTo("same-origin");
        org.assertj.core.api.Assertions.assertThat(r.header("cross-origin-resource-policy")).as(what).isEqualTo("same-origin");
        org.assertj.core.api.Assertions.assertThat(r.header("permissions-policy")).as(what)
                .contains("camera=()", "microphone=()", "geolocation=()", "payment=()");
        org.assertj.core.api.Assertions.assertThat(r.header("cache-control")).as(what).contains("no-store");
        org.assertj.core.api.Assertions.assertThat(r.header("server")).as(what + " must not name the server").isNull();
        org.assertj.core.api.Assertions.assertThat(r.header("x-powered-by")).as(what).isNull();
        org.assertj.core.api.Assertions.assertThat(r.header("strict-transport-security")).as(what + ": HSTS only over HTTPS").isNull();
        org.assertj.core.api.Assertions.assertThat(r.header("access-control-allow-origin")).as(what + ": no CORS").isNull();
    }

    static final String COOKIE = "__Host-chatledger-session";
}
