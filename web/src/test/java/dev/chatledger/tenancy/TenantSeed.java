package dev.chatledger.tenancy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A complete, valid set of synthetic rows for one tenant, written as the schema
 * owner (which is not subject to row-level security). Every table that holds
 * tenant data gets at least one row, so isolation tests have something to hide.
 *
 * <p>The ledger transaction is written in one database transaction with two
 * balanced entries, as the deferred balance trigger requires.
 */
public record TenantSeed(
        UUID tenantId,
        UUID userId,
        UUID customerId,
        UUID orderId,
        UUID orderLineId,
        UUID paymentId,
        UUID receivableAccountId,
        UUID revenueAccountId,
        UUID txnId,
        List<UUID> entryIds,
        UUID matchId,
        UUID flagId,
        UUID auditId,
        UUID catalogItemId) {

    /** Row ids by table name. */
    public Map<String, List<UUID>> idsByTable() {
        return Map.ofEntries(
                Map.entry("tenant", List.of(tenantId)),
                Map.entry("app_user", List.of(userId)),
                Map.entry("customer", List.of(customerId)),
                Map.entry("orders", List.of(orderId)),
                Map.entry("order_line", List.of(orderLineId)),
                Map.entry("payment", List.of(paymentId)),
                Map.entry("account", List.of(receivableAccountId, revenueAccountId)),
                Map.entry("ledger_txn", List.of(txnId)),
                Map.entry("ledger_entry", entryIds),
                Map.entry("match", List.of(matchId)),
                Map.entry("flag", List.of(flagId)),
                Map.entry("audit_log", List.of(auditId)),
                Map.entry("catalog_item", List.of(catalogItemId)));
    }

    public static TenantSeed create(DataSource owner) {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID orderLine = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        UUID receivable = UUID.randomUUID();
        UUID revenue = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        UUID debit = UUID.randomUUID();
        UUID credit = UUID.randomUUID();
        UUID match = UUID.randomUUID();
        UUID flag = UUID.randomUUID();
        UUID audit = UUID.randomUUID();
        UUID catalogItem = UUID.randomUUID();
        String tag = tenant.toString().replace("-", "").substring(0, 12);

        try (Connection c = owner.getConnection()) {
            c.setAutoCommit(false);
            update(c, "INSERT INTO tenant(id, name, currency, scale, slug) VALUES (?, ?, 'XAF', 0, ?)",
                    tenant, "Seed seller " + tag, "seed-" + tag);
            // The stored value is a placeholder, not a real hash and not a real password.
            update(c, "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'SELLER')",
                    user, tenant, "seller-" + tag + "@example.test", TestHashes.UNUSABLE);
            update(c, "INSERT INTO customer(id, tenant_id, display_name) VALUES (?, ?, ?)",
                    customer, tenant, "Synthetic customer");
            update(c, "INSERT INTO orders(id, tenant_id, external_id, customer_id, total_minor, currency, status, raw_text)"
                            + " VALUES (?, ?, ?, ?, 1000, 'XAF', 'PENDING_PAYMENT', 'synthetic order')",
                    order, tenant, "ext-" + tag, customer);
            update(c, "INSERT INTO order_line(id, order_id, tenant_id, label, qty, unit_minor) VALUES (?, ?, ?, 'item', 1, 1000)",
                    orderLine, order, tenant);
            update(c, "INSERT INTO payment(id, tenant_id, external_id, provider, tx_id, amount_minor, currency, status, raw_text, paid_at)"
                            + " VALUES (?, ?, ?, 'PROVIDER_A', ?, 1000, 'XAF', 'RECEIVED', 'synthetic confirmation', now())",
                    payment, tenant, "pay-" + tag, "tx-" + tag);
            update(c, "INSERT INTO account(id, tenant_id, code, name, type) VALUES (?, ?, 'AR', 'Receivable', 'ASSET')",
                    receivable, tenant);
            update(c, "INSERT INTO account(id, tenant_id, code, name, type) VALUES (?, ?, 'REV', 'Revenue', 'REVENUE')",
                    revenue, tenant);
            update(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'ORDER_ACCEPTED', 'ORDER', ?)",
                    txn, tenant, order);
            update(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, 1000, 0)",
                    debit, txn, receivable);
            update(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, 0, 1000)",
                    credit, txn, revenue);
            // A suggestion: it applies nothing, so it needs no posting and does not change any status.
            update(c, "INSERT INTO match(id, tenant_id, order_id, payment_id, score, state)"
                            + " VALUES (?, ?, ?, ?, 90, 'SUGGESTED')",
                    match, tenant, order, payment);
            update(c, "INSERT INTO flag(id, tenant_id, subject_type, payment_id, code, reason) VALUES (?, ?, 'PAYMENT', ?, 'DUPLICATE_TX', 'synthetic')",
                    flag, tenant, payment);
            update(c, "INSERT INTO audit_log(id, tenant_id, actor, action, subject) VALUES (?, ?, ?, 'TEST', 'seed')",
                    audit, tenant, user);
            update(c, "INSERT INTO catalog_item(id, tenant_id, name_key, display_name, unit_minor) VALUES (?, ?, 'seed item', 'Seed item', 1000)",
                    catalogItem, tenant);
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not seed tenant", e);
        }
        return new TenantSeed(tenant, user, customer, order, orderLine, payment, receivable, revenue, txn,
                List.of(debit, credit), match, flag, audit, catalogItem);
    }

    static void update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
