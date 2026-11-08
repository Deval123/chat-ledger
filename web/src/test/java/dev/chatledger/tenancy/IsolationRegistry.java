package dev.chatledger.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.chatledger.tenancy.TenantSeed.update;

/**
 * The register of everything that holds tenant data, and of the exceptions.
 *
 * <p>A new table with tenant data fails the build until it is added here, with a
 * way to insert a valid row. That insert is what lets the behaviour test prove
 * the new table's isolation instead of just asserting that a policy exists.
 *
 * <p>Design: "Tenancy and authentication", decision 2. Task 3.3.
 */
final class IsolationRegistry {

    private IsolationRegistry() {
    }

    enum Kind {
        /** The policy compares the row's own tenant_id with the current tenant. */
        DIRECT,
        /** The row follows a parent table whose policy is DIRECT. */
        VIA_PARENT
    }

    /** Inserts one valid row for {@code rowTenant}, with references taken from {@code refs}. */
    @FunctionalInterface
    interface InsertProbe {
        void insert(Connection c, UUID rowTenant, TenantSeed refs) throws SQLException;
    }

    record TableSpec(Kind kind, String parent, boolean selectOnly, InsertProbe insert) {
        static TableSpec direct(InsertProbe insert) {
            return new TableSpec(Kind.DIRECT, null, false, insert);
        }

        static TableSpec viaParent(String parent, InsertProbe insert) {
            return new TableSpec(Kind.VIA_PARENT, parent, false, insert);
        }
    }

    /** The role every policy is written for. */
    static final String APP_ROLE = "chatledger_app";

    /** Tables that hold no tenant data, with the reason. */
    static final Map<String, String> NON_TENANT_TABLES = Map.of(
            "flyway_schema_history", "Flyway's own bookkeeping; the application role has no privileges on it");

    /**
     * Foreign keys from a table that has no tenant column are allowed only here, with the
     * reason the tenant is still guaranteed.
     */
    static final Map<String, String> FOREIGN_KEY_EXCEPTIONS = Map.of(
            "ledger_entry", "immutable, so no tenant column can be back-filled; the V009/V011 trigger "
                    + "requires the account and the transaction to be visible in the same tenant, and the "
                    + "policy follows ledger_txn");

    /** SECURITY DEFINER functions allowed to exist. Each one needs a reason and a test. */
    static final Set<String> ALLOWED_DEFINER_FUNCTIONS = Set.of(
            "resolve_tenant_for_login",   // login: workspace code to tenant id, nothing else (V013)
            "provision_tenant",           // operator only, via the provisioner role (V013)
            "provision_user");            // operator only, via the provisioner role (V013)

    /** Every repository class and the test that proves it cannot read another tenant. */
    static final Map<String, String> REPOSITORY_CONTRACT_TESTS = Map.of(
            "JdbcLedgerRepository", "dev.chatledger.ledger.LedgerPostgresIT#cross_tenant_reads_return_nothing",
            "AppUserRepository", "dev.chatledger.security.RepositoryIsolationIT#a_user_of_another_tenant_cannot_be_read",
            "AuditLogRepository", "dev.chatledger.security.RepositoryIsolationIT#the_audit_trail_of_another_tenant_cannot_be_read",
            "OrderRepository", "dev.chatledger.orders.OrderRepositoriesIsolationIT#another_tenants_orders_cannot_be_read_or_written",
            "CatalogRepository", "dev.chatledger.orders.OrderRepositoriesIsolationIT#another_tenants_catalog_cannot_be_read",
            "CustomerRepository", "dev.chatledger.orders.OrderRepositoriesIsolationIT#another_tenants_customers_cannot_be_read",
            "TenantRepository", "dev.chatledger.orders.OrderRepositoriesIsolationIT#another_tenants_row_cannot_be_read",
            "PaymentRepository", "dev.chatledger.payments.PaymentRepositoryIsolationIT#another_tenants_payments_cannot_be_read_or_written",
            "MatchRepository", "dev.chatledger.matching.MatchRepositoryIsolationIT#another_tenants_matches_cannot_be_read_or_written");

    static final Map<String, TableSpec> TABLES = Map.ofEntries(
            Map.entry("tenant", new TableSpec(Kind.DIRECT, null, true,
                    (c, t, r) -> update(c, "INSERT INTO tenant(id, name, currency, scale, slug) VALUES (?, 'Probe', 'XAF', 0, ?)",
                            UUID.randomUUID(), "probe-" + tag()))),
            Map.entry("app_user", TableSpec.direct(
                    (c, t, r) -> update(c, "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'HELPER')",
                            UUID.randomUUID(), t, "probe-" + tag() + "@example.test", TestHashes.UNUSABLE))),
            Map.entry("customer", TableSpec.direct(
                    (c, t, r) -> update(c, "INSERT INTO customer(id, tenant_id, display_name) VALUES (?, ?, 'Probe')",
                            UUID.randomUUID(), t))),
            Map.entry("orders", TableSpec.direct(
                    (c, t, r) -> update(c, "INSERT INTO orders(id, tenant_id, external_id, total_minor, currency, status, raw_text)"
                                    + " VALUES (?, ?, ?, 1, 'XAF', 'PENDING_PAYMENT', 'probe')",
                            UUID.randomUUID(), t, "probe-" + tag()))),
            Map.entry("order_line", TableSpec.direct(
                    (c, t, r) -> update(c, "INSERT INTO order_line(id, order_id, tenant_id, label, qty, unit_minor) VALUES (?, ?, ?, 'probe', 1, 1)",
                            UUID.randomUUID(), r.orderId(), t))),
            Map.entry("payment", TableSpec.direct(
                    (c, t, r) -> update(c, "INSERT INTO payment(id, tenant_id, external_id, provider, tx_id, amount_minor, currency, status, raw_text, paid_at)"
                                    + " VALUES (?, ?, ?, 'PROVIDER_A', ?, 1, 'XAF', 'RECEIVED', 'probe', now())",
                            UUID.randomUUID(), t, "probe-pay-" + tag(), "probe-" + tag()))),
            Map.entry("account", TableSpec.direct(
                    (c, t, r) -> update(c, "INSERT INTO account(id, tenant_id, code, name, type) VALUES (?, ?, ?, 'Probe', 'ASSET')",
                            UUID.randomUUID(), t, "P" + tag()))),
            Map.entry("ledger_txn", TableSpec.direct(
                    (c, t, r) -> update(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'PROBE', 'PROBE', ?)",
                            UUID.randomUUID(), t, UUID.randomUUID()))),
            Map.entry("ledger_entry", TableSpec.viaParent("ledger_txn",
                    (c, t, r) -> update(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, 1, 0)",
                            UUID.randomUUID(), r.txnId(), r.receivableAccountId()))),
            Map.entry("match", TableSpec.direct(
                    (c, t, r) -> update(c, "INSERT INTO match(id, tenant_id, order_id, payment_id, score, state, decided_at)"
                                    + " VALUES (?, ?, ?, ?, 90, 'REJECTED', now())",
                            UUID.randomUUID(), t, r.orderId(), r.paymentId()))),
            Map.entry("flag", TableSpec.direct(
                    (c, t, r) -> update(c, "INSERT INTO flag(id, tenant_id, subject_type, subject_id, code, reason) VALUES (?, ?, 'PAYMENT', ?, 'PROBE', 'probe')",
                            UUID.randomUUID(), t, r.paymentId()))),
            Map.entry("catalog_item", TableSpec.direct(
                    (c, t, r) -> update(c, "INSERT INTO catalog_item(id, tenant_id, name_key, display_name, unit_minor) VALUES (?, ?, ?, 'Probe', 1)",
                            UUID.randomUUID(), t, "probe-" + tag()))),
            Map.entry("audit_log", TableSpec.direct(
                    (c, t, r) -> update(c, "INSERT INTO audit_log(id, tenant_id, action, subject) VALUES (?, ?, 'PROBE', 'probe')",
                            UUID.randomUUID(), t))));

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
