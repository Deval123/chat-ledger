package dev.chatledger.matching;

import dev.chatledger.extraction.PaymentTemplates;
import dev.chatledger.ledger.LedgerDatabase;
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
import dev.chatledger.security.MutableClock;
import dev.chatledger.security.RateLimits;
import dev.chatledger.security.RateRule;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orders, payments and the matcher wired by hand against PostgreSQL as the application role, with a clock the test moves: the whole
 * path from a message to a ledger posting. Message dates come from the clock, inside the plausibility window. All names and numbers are
 * synthetic.
 */
final class MatchingFixture {

    static final LedgerDatabase DB = LedgerDatabase.get();
    private static final PaymentTemplates TEMPLATES = PaymentTemplates.of(new ClasspathPaymentTemplateSource().load());
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final String PHONE = "+999 000 000 1234";

    /**
     * The application role's connections, pooled. The plain data source opens a new connection for every statement; sixteen threads doing
     * that on a two-CPU machine (the CI runner) made some connections take more than the driver's ten seconds, and a run failed with
     * "Connect timed out" for no reason that has to do with matching.
     */
    static final com.zaxxer.hikari.HikariDataSource POOL = pool();

    private static com.zaxxer.hikari.HikariDataSource pool() {
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setDataSource(DB.appDataSource());
        config.setMaximumPoolSize(24);
        config.setConnectionTimeout(60_000);
        return new com.zaxxer.hikari.HikariDataSource(config);
    }

    final MutableClock clock = new MutableClock();
    final PhoneKeys phoneKeys = new PhoneKeys(new KeyedHasher(UUID.randomUUID() + "" + UUID.randomUUID()));
    final MatchRepository repository;
    final MatchingService matcher;
    final OrderIngestionService orders;
    final PaymentIngestionService payments;
    final OrderRepository orderRepository;
    final PaymentRepository paymentRepository;

    MatchingFixture() {
        this(MatchConfig.defaults(), MatchingService.StepHook.NONE, true);
    }

    /** @param trigger true to make ingestion call the matcher, as in production; false to ingest only. */
    MatchingFixture(MatchConfig config, MatchingService.StepHook hook, boolean trigger) {
        this(config, hook, trigger ? null : MatchTrigger.NONE);
    }

    /** Ingestion calls the given trigger instead of the matcher (null means the matcher itself). */
    MatchingFixture(MatchTrigger custom) {
        this(MatchConfig.defaults(), MatchingService.StepHook.NONE, custom, MatchingService.ActionHook.NONE);
    }

    /** No trigger from ingestion; the given hook runs after each step of the seller's reverse, reject and payment actions. */
    MatchingFixture(MatchingService.ActionHook actions) {
        this(MatchConfig.defaults(), MatchingService.StepHook.NONE, MatchTrigger.NONE, actions);
    }

    private MatchingFixture(MatchConfig config, MatchingService.StepHook hook, MatchTrigger custom) {
        this(config, hook, custom, MatchingService.ActionHook.NONE);
    }

    private MatchingFixture(MatchConfig config, MatchingService.StepHook hook, MatchTrigger custom, MatchingService.ActionHook actions) {
        this(POOL, config, hook, custom, actions);
    }

    /** The engine runs at ingestion, as in production, over the given (for example pooled) data source of the application role. */
    MatchingFixture(javax.sql.DataSource appDataSource) {
        this(appDataSource, MatchConfig.defaults(), MatchingService.StepHook.NONE, null, MatchingService.ActionHook.NONE);
    }

    private MatchingFixture(javax.sql.DataSource ds, MatchConfig config, MatchingService.StepHook hook, MatchTrigger custom, MatchingService.ActionHook actions) {
        // PostgreSQL keeps microseconds; Linux clocks have nanoseconds (the Mac's do not). A clock with nanoseconds on every machine makes
        // any timestamp that is not cut to microseconds before it is stored fail here, not only on the CI machine.
        clock.advance(java.time.Duration.ofNanos(799));
        repository = new MatchRepository(ds);
        orderRepository = new OrderRepository(ds);
        paymentRepository = new PaymentRepository(ds);
        matcher = new MatchingService(ds, repository, config, new MatchEvents(), clock, hook, actions);
        RateLimits limits = new RateLimits(new InMemoryRateLimiter(clock,
                Map.of(RateLimits.INGESTION_TENANT, RateRule.parse("test", "1000000/1m")), 10_000, Duration.ofHours(1)));
        MatchTrigger t = custom == null ? matcher : custom;
        orders = new OrderIngestionService(new TenantRepository(ds), new CatalogRepository(ds), orderRepository, limits,
                phoneKeys, new IngestionEvents(), t);
        payments = new PaymentIngestionService(new TenantRepository(ds), paymentRepository, TEMPLATES, limits, phoneKeys,
                new PaymentEvents(), clock, t);
    }

    // ── Messages ───────────────────────────────────────────────────────────

    static String id() {
        return "msg-" + UUID.randomUUID();
    }

    static String tx() {
        StringBuilder sb = new StringBuilder("PA");
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 8; i++) {
            sb.append(ALNUM.charAt(r.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    String date(Duration age) {
        return DATE.format(clock.instant().minus(age).atOffset(ZoneOffset.ofHours(1)));
    }

    /** An order message: reference, customer name and phone, one line of the given total. */
    static String orderText(String ref, String name, String phone, long total) {
        return (ref == null ? "" : "Order: " + ref + "\n") + (name == null ? "" : "Name: " + name + "\n")
                + (phone == null ? "" : "Phone: " + phone + "\n") + "1 x rice @ " + total;
    }

    /** A confirmation SMS in the shipped English format with a transaction id; {@code ref} may be null. */
    String sms(long amount, String name, String phone, String ref) {
        String tail = ". Txn " + tx() + (ref == null ? "" : ". Ref " + ref) + ". " + date(Duration.ofMinutes(5));
        return "You received " + amount + " XAF from " + name + " (" + phone + ")" + tail;
    }

    /** The forwarded shape: no transaction id, only the last four digits. */
    String forwarded(long amount, String name, String last4) {
        return "Forwarded: received " + amount + " XAF from " + name + " (" + last4 + ") on " + date(Duration.ofMinutes(5));
    }

    // ── Reading back ───────────────────────────────────────────────────────

    /** Every ledger entry of the tenant as {@code KIND CODE D|C AMOUNT}, ordered by kind, then account code. */
    static List<String> ledger(UUID tenant) {
        List<String> out = new ArrayList<>();
        try (Connection c = DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT t.kind, a.code, e.debit_minor, e.credit_minor FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id "
                             + "JOIN account a ON a.id = e.account_id WHERE t.tenant_id = ? ORDER BY t.kind, a.code, e.debit_minor, e.credit_minor")) {
            ps.setObject(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long debit = rs.getLong(3);
                    out.add(rs.getString(1) + " " + rs.getString(2) + " " + (debit > 0 ? "D" + debit : "C" + rs.getLong(4)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** Debits minus credits of one account code of the tenant (zero if it has no entries). */
    static long balance(UUID tenant, String code) {
        return scalar("SELECT COALESCE(SUM(e.debit_minor) - SUM(e.credit_minor), 0) FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id "
                + "JOIN account a ON a.id = e.account_id WHERE t.tenant_id = ? AND a.code = ?", tenant, code);
    }

    /** Debits minus credits over the whole tenant: always zero. */
    static long trialBalance(UUID tenant) {
        return scalar("SELECT COALESCE(SUM(e.debit_minor) - SUM(e.credit_minor), 0) FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id "
                + "WHERE t.tenant_id = ?", tenant, null);
    }

    /** The first column of every row, as text, for a query with the tenant as its only parameter. */
    static List<String> rows(String sql, UUID tenant) {
        List<String> out = new ArrayList<>();
        try (Connection c = DB.migrateDataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    static long count(String sql, UUID tenant, Object extra) {
        return scalar(sql, tenant, extra);
    }

    private static long scalar(String sql, UUID tenant, Object extra) {
        try (Connection c = DB.migrateDataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenant);
            if (extra != null) {
                ps.setObject(2, extra);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static String status(String table, UUID id) {
        try (Connection c = DB.migrateDataSource().getConnection(); PreparedStatement ps = c.prepareStatement("SELECT status FROM " + table + " WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A user of the tenant with the given role ('SELLER' or 'HELPER'), created as the schema owner. */
    static UUID user(UUID tenant, String role) {
        UUID id = UUID.randomUUID();
        try (Connection c = DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setString(3, "user-" + id + "@example.test");
            ps.setString(4, dev.chatledger.tenancy.TestHashes.UNUSABLE);
            ps.setString(5, role);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }
}
