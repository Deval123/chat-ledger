package dev.chatledger.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.chatledger.demo.ops.DemoWorld;
import dev.chatledger.ledger.LedgerDatabase;
import dev.chatledger.web.ChatLedgerApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The real application as the demo deployment runs it (task 9): {@code app.demo.enabled=true} on the demo's own database in DEMO mode
 * (the application would not start otherwise), with the secret the seed used. A sibling of {@link AbstractWebIT}, not a subclass: its
 * database properties cannot be replaced from a subclass (Spring registers the base class's last). The limits are out of the way here;
 * {@code DemoLimitsIT} and {@code DemoProxyIT} set real ones.
 */
@SpringBootTest(classes = ChatLedgerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=false", "spring.datasource.hikari.maximum-pool-size=4",
                // Each demo context stays cached: it gives its connections back when idle, so the shared cluster is not exhausted.
                "spring.datasource.hikari.minimum-idle=0", "spring.datasource.hikari.idle-timeout=10000"})
@ActiveProfiles("probe")
@Import(AbstractWebIT.TestClockConfig.class)
// Out of the way here; a subclass's own @TestPropertySource replaces these (DemoLimitsIT, DemoProxyIT).
@org.springframework.test.context.TestPropertySource(properties = {
        "app.demo.page-per-address=1000000/1m", "app.demo.claim-per-address=1000000/1m", "app.demo.claim-total=1000000/1m",
        "app.demo.scenario-per-tenant=1000000/1m"})
abstract class AbstractDemoWebIT {

    static final LedgerDatabase DB = DemoWorld.DB;
    static final String COOKIE = AbstractWebIT.COOKIE;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DemoWorld.init();
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> LedgerDatabase.APP_USER);
        registry.add("spring.datasource.password", DB::appPassword);
        registry.add("spring.flyway.user", () -> UUID.randomUUID().toString());
        registry.add("spring.flyway.password", () -> UUID.randomUUID().toString());
        registry.add("app.secret", () -> DemoWorld.SECRET);
        registry.add("app.demo.enabled", () -> "true");
        registry.add("app.security.limits.login-per-ip", () -> "1000000/1m");
        registry.add("app.security.limits.login-per-account", () -> "1000000/1m");
        registry.add("app.security.limits.login-page-per-ip", () -> "1000000/1m");
    }

    @LocalServerPort
    int port;

    @Autowired
    MutableClock clock;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        root.setLevel(Level.DEBUG);
    }

    @AfterEach
    void releaseLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(logs);
        root.setLevel(Level.INFO);
    }

    String loggedText() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : List.copyOf(logs.list)) {
            sb.append(e.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }

    Browser browser() {
        return new Browser(port);
    }

    static String unique() {
        return AbstractWebIT.unique();
    }

    static String newPassword() {
        return AbstractWebIT.newPassword();
    }

    static void assertSecurityHeaders(Browser.Response r, String what) {
        AbstractWebIT.assertSecurityHeaders(r, what);
    }

    static String path(Browser.Response r) {
        return BoardIT.path(r);
    }

    /** A browser signed in through the password form; fails the test if sign-in does not succeed. */
    Browser signedIn(String workspace, String email, String password) {
        Browser b = browser();
        Browser.Response page = b.get("/login");
        Browser.Response r = b.post("/login", Map.of("_csrf", Browser.csrf(page), "workspace", workspace, "email", email, "password", password));
        if (r.status() != 302 || !r.header("location").endsWith("/app")) {
            throw new AssertionError("sign-in failed: " + r.status() + " " + r.header("location"));
        }
        return b;
    }

    /** A visitor's browser after one click on "Try the demo". */
    Browser entered() {
        Browser b = browser();
        Browser.Response page = b.get("/demo");
        Browser.Response in = b.post("/demo", Map.of("_csrf", Browser.csrf(page)));
        if (in.status() != 302 || !path(in).equals("/app")) {
            throw new AssertionError("the demo was not entered: " + in.status() + " " + in.header("location"));
        }
        return b;
    }

    /** A form post with the CSRF token of the current session (read from the board). */
    Browser.Response demoPost(Browser b, String path) {
        return b.post(path, Map.of("_csrf", Browser.csrf(b.get("/app"))));
    }

    Browser.Response pasteOrder(Browser b, String text) {
        return b.post("/app/orders", Map.of("_csrf", Browser.csrf(b.get("/app")), "mid", UUID.randomUUID().toString(), "text", text));
    }

    /** One value from the demo database, read as its owner. */
    static String demoOne(String sql, Object... args) {
        return DemoWorld.ownerOne(sql, args);
    }

    static List<String> demoRows(String sql, Object... args) {
        return DemoWorld.owner(sql, args);
    }

    static long claimed() {
        return Long.parseLong(demoOne("SELECT count(*)::text FROM demo_tenant WHERE state = 'CLAIMED'"));
    }
}
