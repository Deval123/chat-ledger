package dev.chatledger.demo.ops;

import dev.chatledger.board.BoardRepository;
import dev.chatledger.demo.DemoMessages;
import dev.chatledger.flags.SuspicionWriter;
import dev.chatledger.matching.MatchConfig;
import dev.chatledger.matching.MatchEvents;
import dev.chatledger.matching.MatchRepository;
import dev.chatledger.matching.MatchingService;
import dev.chatledger.orders.CatalogRepository;
import dev.chatledger.orders.IngestionEvents;
import dev.chatledger.orders.OrderIngestionService;
import dev.chatledger.orders.OrderRepository;
import dev.chatledger.orders.PhoneKeys;
import dev.chatledger.orders.TenantRepository;
import dev.chatledger.payments.ClasspathPaymentTemplateSource;
import dev.chatledger.payments.PaymentEvents;
import dev.chatledger.payments.PaymentIngestionService;
import dev.chatledger.payments.PaymentRepository;
import dev.chatledger.security.InMemoryRateLimiter;
import dev.chatledger.security.KeyedHasher;
import dev.chatledger.security.PasswordHashing;
import dev.chatledger.security.RateLimits;
import dev.chatledger.security.RateRule;
import org.springframework.boot.convert.DurationStyle;

import javax.sql.DataSource;
import java.io.PrintStream;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The demo operator's commands (task 9, D6 and D24), run like {@code provision-*}: {@code java -jar web.jar demo-init} and
 * {@code demo-refresh [--pool N] [--every 600s]}. No web server starts and no Spring context: the command builds the same ingestion
 * services, matcher and suspicion rules the web process uses, on the application's login, so the seed is real input. The pool is changed
 * only through the V020 functions, on the demo operator's login.
 *
 * <p>Configuration comes from the environment only: {@code DB_URL}, {@code DB_USER} and {@code DB_PASSWORD} (the application login),
 * {@code DEMO_DB_USER} and {@code DEMO_DB_PASSWORD} (the demo operator), and {@code APP_SECRET} (the web process's own, so phone hashes
 * agree). Nothing secret is printed; a database failure is reported by SQLSTATE only.
 *
 * <p>With {@code --every}, it runs a pass, sleeps, and runs again until it is stopped. The container entrypoint starts that loop as a
 * second process next to the web process ({@code docker/demo-entrypoint.sh}); the web process never runs it. Two runners never work at
 * once: each pass takes the refresh lock first.
 */
public final class DemoCommand {

    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int REFUSED = 3;
    public static final int INFRASTRUCTURE = 4;

    public static final String INIT = "demo-init";
    public static final String REFRESH = "demo-refresh";

    static final Duration MIN_EVERY = Duration.ofSeconds(60);
    static final Duration MAX_EVERY = Duration.ofSeconds(3600);

    private static final List<String> REQUIRED = List.of("DB_URL", "DB_USER", "DB_PASSWORD", "DEMO_DB_USER", "DEMO_DB_PASSWORD", "APP_SECRET");

    /** Opens a data source for one login; replaced in tests. */
    public interface Databases {
        DataSource open(String url, String user, String password);
    }

    /** Waits between passes; replaced in tests. Returns false to stop the loop. */
    public interface Sleeper {
        boolean sleep(Duration d);
    }

    private final Map<String, String> env;
    private final Databases databases;
    private final Sleeper sleeper;
    private final PrintStream out;
    private final PrintStream err;

    public DemoCommand(Map<String, String> env, Databases databases, Sleeper sleeper, PrintStream out, PrintStream err) {
        this.env = env;
        this.databases = databases;
        this.sleeper = sleeper;
        this.out = out;
        this.err = err;
    }

    public static int main(String[] args) {
        Databases real = (url, user, password) -> {
            org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
            ds.setUrl(url);
            ds.setUser(user);
            ds.setPassword(password);
            return ds;
        };
        Sleeper sleep = d -> {
            try {
                Thread.sleep(d.toMillis());
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
        return new DemoCommand(System.getenv(), real, sleep, System.out, System.err).run(args);
    }

    public int run(String[] args) {
        if (args.length == 0 || !(INIT.equals(args[0]) || REFRESH.equals(args[0]))) {
            return usage("Unknown command.");
        }
        Map<String, String> flags = new HashMap<>();
        Set<String> allowed = REFRESH.equals(args[0]) ? Set.of("pool", "every") : Set.of();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                return usage("Unexpected argument.");
            }
            String name = arg.substring(2);
            String value = null;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            if (!allowed.contains(name)) {
                return usage("Unknown option --" + name + ".");   // a password or secret is never an option either
            }
            if (value == null) {
                if (i + 1 >= args.length) {
                    return usage("Option --" + name + " needs a value.");
                }
                value = args[++i];
            }
            if (flags.put(name, value) != null) {
                return usage("Option --" + name + " was given twice.");
            }
        }
        int pool = DemoRefresh.DEFAULT_POOL;
        if (flags.containsKey("pool")) {
            try {
                pool = Integer.parseInt(flags.get("pool").strip());
            } catch (NumberFormatException e) {
                return usage("--pool must be a whole number.");
            }
            if (pool < DemoRefresh.MIN_POOL || pool > DemoRefresh.MAX_POOL) {
                return usage("--pool must be between " + DemoRefresh.MIN_POOL + " and " + DemoRefresh.MAX_POOL + ".");
            }
        }
        Duration every = null;
        if (flags.containsKey("every")) {
            try {
                every = DurationStyle.SIMPLE.parse(flags.get("every").strip());
            } catch (RuntimeException e) {
                return usage("--every must be a duration such as 600s or 10m.");
            }
            if (every.compareTo(MIN_EVERY) < 0 || every.compareTo(MAX_EVERY) > 0) {
                return usage("--every must be between 60s and 3600s.");
            }
        }

        for (String name : REQUIRED) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                return usage("Set " + name + " (see .env.example).");
            }
            if (value.strip().startsWith("<") && value.strip().endsWith(">")) {
                err.println("Refused: " + name + " still holds a placeholder from .env.example.");
                return REFUSED;
            }
        }
        if (!env.get("DB_URL").startsWith("jdbc:postgresql:")) {
            return usage("DB_URL must be a jdbc:postgresql: URL.");
        }
        if (env.get("DEMO_DB_USER").equals(env.get("DB_USER"))) {
            err.println("Refused: DEMO_DB_USER must be the demo operator's own login, not the application's.");
            return REFUSED;
        }
        DataSource operator = databases.open(env.get("DB_URL"), env.get("DEMO_DB_USER"), env.get("DEMO_DB_PASSWORD"));
        if (INIT.equals(args[0])) {
            return init(operator);
        }

        DemoRefresh refresh;
        try {
            DataSource app = databases.open(env.get("DB_URL"), env.get("DB_USER"), env.get("DB_PASSWORD"));
            refresh = new DemoRefresh(operator, seeder(app, env.get("APP_SECRET")), PasswordHashing.create());
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("Refused: APP_SECRET is not usable (it must be the web process's own secret, at least 32 characters).");
            return REFUSED;
        }
        while (true) {
            try {
                DemoRefresh.Report report = refresh.run(pool);
                out.println(report.skipped() ? "Another refresh is running; this pass did nothing."
                        : "Demo pool: retired " + report.retired() + ", created " + report.created() + ", failed " + report.failed() + ".");
                if (every == null) {
                    return report.failed() > 0 ? INFRASTRUCTURE : OK;
                }
            } catch (IllegalStateException e) {
                err.println(e.getMessage());
                if (every == null) {
                    return INFRASTRUCTURE;
                }
            }
            if (!sleeper.sleep(every)) {
                return OK;
            }
        }
    }

    private int init(DataSource operator) {
        try (Connection c = operator.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT demo_init()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            out.println("This database is a demo deployment (mode " + rs.getString(1) + ").");
            return OK;
        } catch (SQLException e) {
            String state = e.getSQLState() == null ? "" : e.getSQLState();
            if ("55000".equals(state)) {
                err.println("Refused: this database holds a real workspace. A demo needs its own, empty database.");
                return REFUSED;
            }
            if ("42501".equals(state)) {
                err.println("The demo operator login is not allowed to do this. Grant it chatledger_demo_operator.");
                return INFRASTRUCTURE;
            }
            err.println("The database could not be reached or failed (SQLSTATE " + (state.isEmpty() ? "none" : state) + ").");
            return INFRASTRUCTURE;
        }
    }

    /** The services the web process runs, built on the application's login, with the default thresholds. */
    public record Services(OrderIngestionService orders, PaymentIngestionService payments, MatchingService matcher, BoardRepository board,
                           DemoMessages messages) {
        public DemoSeeder seeder() {
            return new DemoSeeder(orders, payments, matcher, board, messages);
        }
    }

    static DemoSeeder seeder(DataSource app, String appSecret) {
        return services(app, appSecret, Clock.systemUTC()).seeder();
    }

    public static Services services(DataSource app, String appSecret, Clock clock) {
        PhoneKeys phoneKeys = new PhoneKeys(new KeyedHasher(appSecret));
        SuspicionWriter suspicion = SuspicionWriter.withDefaults(clock);
        MatchingService matcher = new MatchingService(app, new MatchRepository(app), MatchConfig.defaults(), new MatchEvents(), clock, suspicion);
        RateLimits limits = new RateLimits(new InMemoryRateLimiter(clock,
                Map.of(RateLimits.INGESTION_TENANT, RateRule.parse("ingestion-per-tenant", "60/1m/30")), 10_000, Duration.ofHours(1)));
        var specs = new ClasspathPaymentTemplateSource().load();
        OrderIngestionService orders = new OrderIngestionService(new TenantRepository(app), new CatalogRepository(app), new OrderRepository(app),
                limits, phoneKeys, new IngestionEvents(), matcher, suspicion);
        PaymentIngestionService payments = new PaymentIngestionService(new TenantRepository(app), new PaymentRepository(app),
                dev.chatledger.extraction.PaymentTemplates.of(specs), limits, phoneKeys, new PaymentEvents(), clock, matcher, suspicion);
        return new Services(orders, payments, matcher, new BoardRepository(app), new DemoMessages(specs, clock, new SecureRandom()));
    }

    private int usage(String problem) {
        err.println(problem);
        err.println("Usage:");
        err.println("  demo-init                                  make this empty database a demo deployment (once)");
        err.println("  demo-refresh [--pool 10..50] [--every 60s..3600s]");
        err.println("Database access comes from DB_URL, DB_USER, DB_PASSWORD (the application login) and DEMO_DB_USER, DEMO_DB_PASSWORD");
        err.println("(the demo operator login); APP_SECRET must be the web process's own.");
        return USAGE;
    }
}
