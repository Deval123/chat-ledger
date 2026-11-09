package dev.chatledger.board;

import dev.chatledger.tenancy.DbTime;
import dev.chatledger.tenancy.TenantScope;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The rows of the three CSV files. Read-only, tenant-scoped, bounded: the caller counts first and refuses a range above its cap, and
 * every stream stops at the cap anyway. Rows are handed to a sink one at a time so a large file is never held in memory.
 *
 * <p>Nothing here cleans text: {@link CsvWriter} does that for every text cell.
 */
@Repository
public class ExportRepository {

    public record OrderRow(UUID id, Instant at, String status, String ref, String who, String last4, Long totalMinor, long appliedMinor,
                           String currency, String note) {
    }

    public record PaymentRow(UUID id, Instant at, Instant paidAt, String status, String provider, String txId, String who, String last4,
                             String ref, Long amountMinor, String currency, UUID orderId) {
    }

    public record LedgerRow(UUID txnId, Instant at, String kind, String refType, UUID refId, String accountCode, String accountName,
                            long debitMinor, long creditMinor) {
    }

    @FunctionalInterface
    public interface Sink<T> {
        void accept(T row) throws SQLException;
    }

    private final TenantScope scope;

    public ExportRepository(DataSource dataSource) {
        this.scope = new TenantScope(dataSource);
    }

    public long countOrders(UUID tenantId, Instant from, Instant to) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM orders WHERE tenant_id = ? AND created_at >= ? AND created_at < ?")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, DbTime.utc(from));
                    ps.setObject(3, DbTime.utc(to));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count the rows to export", e);
        }
    }

    public long countPayments(UUID tenantId, Instant from, Instant to) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM payment WHERE tenant_id = ? AND created_at >= ? AND created_at < ?")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, DbTime.utc(from));
                    ps.setObject(3, DbTime.utc(to));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count the rows to export", e);
        }
    }

    public long countLedger(UUID tenantId, Instant from, Instant to) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id WHERE t.tenant_id = ? AND t.created_at >= ? AND t.created_at < ?")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, DbTime.utc(from));
                    ps.setObject(3, DbTime.utc(to));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count the rows to export", e);
        }
    }

    /** Streams at most {@code cap} orders recorded in [from, to), oldest first. */
    public void orders(UUID tenantId, Instant from, Instant to, int cap, Sink<OrderRow> sink) {
        try {
            scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT o.id, o.created_at, o.status, o.order_ref, cu.display_name, cu.phone_last4, o.total_minor, o.currency, o.delivery_note, "
                                + "COALESCE((SELECT SUM(m.amount_minor) FROM match m WHERE m.tenant_id = o.tenant_id AND m.order_id = o.id "
                                + "AND m.state IN ('AUTO', 'CONFIRMED')), 0) AS applied "
                                + "FROM orders o LEFT JOIN customer cu ON cu.id = o.customer_id AND cu.tenant_id = o.tenant_id "
                                + "WHERE o.tenant_id = ? AND o.created_at >= ? AND o.created_at < ? ORDER BY o.created_at, o.id LIMIT ?")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, DbTime.utc(from));
                    ps.setObject(3, DbTime.utc(to));
                    ps.setInt(4, cap);
                    ps.setFetchSize(500);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long total = rs.getLong("total_minor");
                            Long totalOrNull = rs.wasNull() ? null : total;
                            String last4 = rs.getString("phone_last4");
                            sink.accept(new OrderRow(rs.getObject("id", UUID.class), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                                    rs.getString("status"), rs.getString("order_ref"), rs.getString("display_name"), last4 == null ? null : last4.strip(),
                                    totalOrNull, rs.getLong("applied"), rs.getString("currency").strip(), rs.getString("delivery_note")));
                        }
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the orders to export", e);
        }
    }

    /** Streams at most {@code cap} payments recorded in [from, to), oldest first. */
    public void payments(UUID tenantId, Instant from, Instant to, int cap, Sink<PaymentRow> sink) {
        try {
            scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT p.id, p.created_at, p.paid_at, p.status, p.provider, p.tx_id, p.sender_name, p.sender_last4, p.reference, "
                                + "p.amount_minor, p.currency, (SELECT m.order_id FROM match m WHERE m.tenant_id = p.tenant_id AND m.payment_id = p.id "
                                + "AND m.state IN ('AUTO', 'CONFIRMED') LIMIT 1) AS order_id "
                                + "FROM payment p WHERE p.tenant_id = ? AND p.created_at >= ? AND p.created_at < ? ORDER BY p.created_at, p.id LIMIT ?")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, DbTime.utc(from));
                    ps.setObject(3, DbTime.utc(to));
                    ps.setInt(4, cap);
                    ps.setFetchSize(500);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long amount = rs.getLong("amount_minor");
                            Long amountOrNull = rs.wasNull() ? null : amount;
                            String last4 = rs.getString("sender_last4");
                            OffsetDateTime paidAt = rs.getObject("paid_at", OffsetDateTime.class);
                            String currency = rs.getString("currency");
                            sink.accept(new PaymentRow(rs.getObject("id", UUID.class), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                                    paidAt == null ? null : paidAt.toInstant(), rs.getString("status"), rs.getString("provider"), rs.getString("tx_id"),
                                    rs.getString("sender_name"), last4 == null ? null : last4.strip(), rs.getString("reference"), amountOrNull,
                                    currency == null ? null : currency.strip(), rs.getObject("order_id", UUID.class)));
                        }
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the payments to export", e);
        }
    }

    /** Streams at most {@code cap} ledger entries of the transactions created in [from, to), oldest first. */
    public void ledger(UUID tenantId, Instant from, Instant to, int cap, Sink<LedgerRow> sink) {
        try {
            scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT t.id, t.created_at, t.kind, t.ref_type, t.ref_id, a.code, a.name, e.debit_minor, e.credit_minor "
                                + "FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id JOIN account a ON a.id = e.account_id AND a.tenant_id = t.tenant_id "
                                + "WHERE t.tenant_id = ? AND t.created_at >= ? AND t.created_at < ? ORDER BY t.created_at, t.id, e.id LIMIT ?")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, DbTime.utc(from));
                    ps.setObject(3, DbTime.utc(to));
                    ps.setInt(4, cap);
                    ps.setFetchSize(500);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            sink.accept(new LedgerRow(rs.getObject("id", UUID.class), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                                    rs.getString("kind"), rs.getString("ref_type"), rs.getObject("ref_id", UUID.class), rs.getString("code"),
                                    rs.getString("name"), rs.getLong("debit_minor"), rs.getLong("credit_minor")));
                        }
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the ledger to export", e);
        }
    }
}
