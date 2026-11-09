package dev.chatledger.orders;

import dev.chatledger.extraction.TextKeys;
import dev.chatledger.ledger.LedgerDatabase;
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
import java.util.Map;
import java.util.UUID;

/**
 * The ingestion service wired by hand against the real PostgreSQL as the application role, with a clock the test
 * moves. No Spring context, so it starts fast. Credentials and secrets are random for each run.
 */
final class IngestionFixture {

    static final LedgerDatabase DB = LedgerDatabase.get();

    final MutableClock clock = new MutableClock();
    final KeyedHasher hasher = new KeyedHasher(UUID.randomUUID() + "" + UUID.randomUUID());
    final PhoneKeys phoneKeys = new PhoneKeys(hasher);
    final TenantRepository tenants = new TenantRepository(DB.appDataSource());
    final CatalogRepository catalog = new CatalogRepository(DB.appDataSource());
    final CustomerRepository customers = new CustomerRepository(DB.appDataSource());
    final OrderRepository orders = new OrderRepository(DB.appDataSource());
    final AuditLogRepository audit = new AuditLogRepository(DB.appDataSource());
    final RateLimits limits;
    final OrderIngestionService service;

    /** The default limit of the design: 60 a minute, burst 30. */
    IngestionFixture() {
        this("60/1m/30");
    }

    IngestionFixture(String ingestionRule) {
        limits = new RateLimits(new InMemoryRateLimiter(clock,
                Map.of(RateLimits.INGESTION_TENANT, RateRule.parse("test", ingestionRule)), 10_000, Duration.ofHours(1)));
        service = new OrderIngestionService(tenants, catalog, orders, limits, phoneKeys, new IngestionEvents(), dev.chatledger.matching.MatchTrigger.NONE, dev.chatledger.flags.SuspicionWriter.withDefaults(java.time.Clock.systemUTC()));
    }

    UUID tenant() {
        return DB.createTenant();
    }

    UUID tenant(String currency, int scale) {
        return DB.createTenant(currency, scale);
    }

    /** The application role cannot write the catalog; the owner seeds it, as the operator or demo seed will. */
    void seedCatalog(UUID tenant, String name, long unitMinor) {
        try (Connection c = DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO catalog_item(id, tenant_id, name_key, display_name, unit_minor) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            ps.setString(3, TextKeys.key(name));
            ps.setString(4, name);
            ps.setLong(5, unitMinor);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
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

    IngestionResult ingest(UUID tenant, String externalId, String text) {
        return service.ingest(tenant, null, externalId, text);
    }

    static String id() {
        return "msg-" + UUID.randomUUID();
    }
}
