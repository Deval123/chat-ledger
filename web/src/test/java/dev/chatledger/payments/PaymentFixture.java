package dev.chatledger.payments;

import dev.chatledger.extraction.PaymentTemplates;
import dev.chatledger.ledger.LedgerDatabase;
import dev.chatledger.orders.PhoneKeys;
import dev.chatledger.orders.TenantRepository;
import dev.chatledger.security.AuditLogRepository;
import dev.chatledger.security.InMemoryRateLimiter;
import dev.chatledger.security.KeyedHasher;
import dev.chatledger.security.MutableClock;
import dev.chatledger.security.RateLimits;
import dev.chatledger.security.RateRule;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * The payment service wired by hand against the real PostgreSQL as the application role, with the templates that ship in the
 * jar and a clock the test moves. Message dates are generated from that clock, inside the plausibility window, so no test
 * depends on the day it runs. Secrets are random for each run; every name and number is synthetic.
 */
final class PaymentFixture {

    static final LedgerDatabase DB = LedgerDatabase.get();
    private static final PaymentTemplates TEMPLATES = PaymentTemplates.of(new ClasspathPaymentTemplateSource().load());
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    final MutableClock clock = new MutableClock();
    final KeyedHasher hasher = new KeyedHasher(UUID.randomUUID() + "" + UUID.randomUUID());
    final PhoneKeys phoneKeys = new PhoneKeys(hasher);
    final TenantRepository tenants = new TenantRepository(DB.appDataSource());
    final PaymentRepository payments = new PaymentRepository(DB.appDataSource());
    final AuditLogRepository audit = new AuditLogRepository(DB.appDataSource());
    final RateLimits limits;
    final PaymentIngestionService service;

    /** The default limit of the design: 60 a minute, burst 30. */
    PaymentFixture() {
        this("60/1m/30");
    }

    PaymentFixture(String ingestionRule) {
        limits = new RateLimits(new InMemoryRateLimiter(clock,
                Map.of(RateLimits.INGESTION_TENANT, RateRule.parse("test", ingestionRule)), 10_000, Duration.ofHours(1)));
        service = new PaymentIngestionService(tenants, payments, TEMPLATES, limits, phoneKeys, new PaymentEvents(), clock);
    }

    UUID tenant() {
        return DB.createTenant();
    }

    UUID tenant(String currency, int scale) {
        return DB.createTenant(currency, scale);
    }

    PaymentIngestionResult ingest(UUID tenant, String externalId, String text) {
        return service.ingest(tenant, null, externalId, text);
    }

    static String id() {
        return "sms-" + UUID.randomUUID();
    }

    static String txId() {
        StringBuilder sb = new StringBuilder("PA");
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 8; i++) {
            sb.append(ALNUM.charAt(r.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    /** The date as the message writes it: the clock's time minus a few minutes, in the template's fixed zone (+01:00). */
    String date(Duration age) {
        return DATE.format(clock.instant().minus(age).atOffset(ZoneOffset.ofHours(1)));
    }

    /** The same instant the message writes, truncated to the minute, as the service stores it. */
    Instant instantOf(Duration age) {
        return clock.instant().minus(age).atOffset(ZoneOffset.ofHours(1)).withSecond(0).withNano(0).toInstant();
    }

    String sms(String tx, int amount, String phone) {
        return "You received " + amount + " XAF from Ana K. (" + phone + "). Txn " + tx + ". " + date(Duration.ofMinutes(5));
    }

    String sms(String tx) {
        return sms(tx, 5000, "+999 000 000 1234");
    }

    String smsWithRef(String tx, String ref) {
        return "You received 5000 XAF from Ana K. (+999 000 000 1234). Txn " + tx + ". Ref " + ref + ". " + date(Duration.ofMinutes(5));
    }

    String forwarded() {
        return "Forwarded: received 5000 XAF from Ana K. (1234) on " + date(Duration.ofMinutes(5));
    }

    /** A real user of the tenant, created by the owner (the audit trail's actor must be one). */
    UUID user(UUID tenant) {
        UUID id = UUID.randomUUID();
        try (Connection c = DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'SELLER')")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setString(3, "user-" + id + "@example.test");
            ps.setString(4, dev.chatledger.tenancy.TestHashes.UNUSABLE);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }
}
