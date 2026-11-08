package dev.chatledger.matching;

import dev.chatledger.tenancy.TenantScope;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the candidates the matcher scores and stores and reads matches. Every call runs in a {@link TenantScope}, so row-level security
 * applies, and every statement names the tenant as well (a test runs the repository as the schema owner, which row-level security does
 * not bind, to prove the statements scope by themselves). Every statement is a complete string literal with bound parameters.
 *
 * <p>Nothing here selects {@code raw_text}: scoring cannot read the text of a message. The write that moves money (the atomic apply
 * of a match with its posting and status changes) is added with the matching service; this class stores suggestions.
 */
@Repository
public class MatchRepository {

    /** A stored match. {@code toString} prints ids and the state only. */
    public record Stored(UUID id, UUID orderId, UUID paymentId, String state, Long amountMinor, long excessMinor, int score,
                         String reasonsJson, UUID ledgerTxnId, UUID decidedBy, Instant decidedAt) {
        @Override
        public String toString() {
            return "Match[id=" + id + ", state=" + state + "]";
        }
    }

    private final TenantScope scope;

    public MatchRepository(DataSource dataSource) {
        this.scope = new TenantScope(dataSource);
    }

    /** Stores a suggestion (no amounts, no posting). Empty if the pair already has an open match: nothing is written. */
    public Optional<UUID> insertSuggestion(UUID tenantId, UUID orderId, UUID paymentId, int score, String reasonsJson) {
        try {
            return scope.call(tenantId, c -> {
                UUID id = UUID.randomUUID();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO match(id, tenant_id, order_id, payment_id, score, reasons_json, state) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 'SUGGESTED') ON CONFLICT DO NOTHING RETURNING id")) {
                    ps.setObject(1, id);
                    ps.setObject(2, tenantId);
                    ps.setObject(3, orderId);
                    ps.setObject(4, paymentId);
                    ps.setInt(5, score);
                    ps.setString(6, reasonsJson);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(id) : Optional.<UUID>empty();
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store a match", e);
        }
    }

    public Optional<Stored> find(UUID tenantId, UUID matchId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, order_id, payment_id, state, amount_minor, excess_minor, score, reasons_json, ledger_txn_id, decided_by, decided_at "
                                + "FROM match WHERE tenant_id = ? AND id = ?")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, matchId);
                    List<Stored> rows = readAll(ps);
                    return rows.isEmpty() ? Optional.<Stored>empty() : Optional.of(rows.get(0));
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read matches", e);
        }
    }

    public List<Stored> forPayment(UUID tenantId, UUID paymentId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, order_id, payment_id, state, amount_minor, excess_minor, score, reasons_json, ledger_txn_id, decided_by, decided_at "
                                + "FROM match WHERE tenant_id = ? AND payment_id = ? ORDER BY created_at, id")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, paymentId);
                    return readAll(ps);
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read matches", e);
        }
    }

    public List<Stored> forOrder(UUID tenantId, UUID orderId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, order_id, payment_id, state, amount_minor, excess_minor, score, reasons_json, ledger_txn_id, decided_by, decided_at "
                                + "FROM match WHERE tenant_id = ? AND order_id = ? ORDER BY created_at, id")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, orderId);
                    return readAll(ps);
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read matches", e);
        }
    }

    private static List<Stored> readAll(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Stored> out = new ArrayList<>();
            while (rs.next()) {
                out.add(read(rs));
            }
            return out;
        }
    }

    /** The sum of the order's active matches: what is already applied to it. */
    public long appliedMinor(UUID tenantId, UUID orderId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COALESCE(SUM(amount_minor), 0) FROM match WHERE tenant_id = ? AND order_id = ? AND state IN ('AUTO', 'CONFIRMED')")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read matches", e);
        }
    }

    /** True if the seller rejected this pair before: the matcher does not suggest it again. */
    public boolean wasRejected(UUID tenantId, UUID orderId, UUID paymentId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM match WHERE tenant_id = ? AND order_id = ? AND payment_id = ? AND state = 'REJECTED' AND decided_by IS NOT NULL")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, orderId);
                    ps.setObject(3, paymentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next();
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read matches", e);
        }
    }

    public long count(UUID tenantId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM match WHERE tenant_id = ?")) {
                    ps.setObject(1, tenantId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count matches", e);
        }
    }

    /**
     * The tenant's open orders in {@code currency}, most recent first, at most {@code limit}: status {@code PENDING_PAYMENT} or
     * {@code PARTIALLY_PAID} (which always have a total), something left to pay. Parsed fields only. {@code referenceShared} is true when another open
     * order has the same reference key.
     */
    public List<OrderFacts> openOrders(UUID tenantId, String currency, int limit) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT x.id, x.total_minor, x.applied, x.currency, x.created_at, x.order_ref, cu.display_name, cu.phone_hash, cu.phone_last4, x.ref_n "
                                + "FROM (SELECT o.id, o.tenant_id, o.total_minor, o.currency, o.created_at, o.order_ref, o.customer_id, "
                                + "COALESCE((SELECT SUM(m.amount_minor) FROM match m WHERE m.tenant_id = o.tenant_id AND m.order_id = o.id "
                                + "AND m.state IN ('AUTO', 'CONFIRMED')), 0) AS applied, "
                                + "CASE WHEN o.order_ref IS NULL THEN 1 ELSE COUNT(*) OVER (PARTITION BY upper(o.order_ref)) END AS ref_n "
                                + "FROM orders o WHERE o.tenant_id = ? AND o.status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID') "
                                + "AND o.currency = ?) x "
                                + "LEFT JOIN customer cu ON cu.id = x.customer_id AND cu.tenant_id = x.tenant_id "
                                + "WHERE x.applied < x.total_minor ORDER BY x.created_at DESC, x.id LIMIT ?")) {
                    ps.setObject(1, tenantId);
                    ps.setString(2, currency);
                    ps.setInt(3, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<OrderFacts> out = new ArrayList<>();
                        while (rs.next()) {
                            String last4 = rs.getString("phone_last4");
                            out.add(new OrderFacts(rs.getObject("id", UUID.class), rs.getLong("total_minor"), rs.getLong("applied"),
                                    rs.getString("currency").strip(), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                                    rs.getString("order_ref"), rs.getString("display_name"), rs.getString("phone_hash"),
                                    last4 == null ? null : last4.strip(), rs.getLong("ref_n") > 1));
                        }
                        return out;
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read open orders", e);
        }
    }

    /** The tenant's payments free to be matched ({@code RECEIVED} or {@code UNVERIFIED}) in {@code currency}, most recent first, at most {@code limit}. */
    public List<PaymentFacts> openPayments(UUID tenantId, String currency, int limit) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT p.id, p.amount_minor, p.currency, p.tx_id IS NOT NULL AS has_tx, p.reference, p.sender_name, p.sender_phone_hash, "
                                + "p.sender_last4, p.paid_at FROM payment p WHERE p.tenant_id = ? AND p.status IN ('RECEIVED', 'UNVERIFIED') "
                                + "AND p.currency = ? ORDER BY p.created_at DESC, p.id LIMIT ?")) {
                    ps.setObject(1, tenantId);
                    ps.setString(2, currency);
                    ps.setInt(3, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<PaymentFacts> out = new ArrayList<>();
                        while (rs.next()) {
                            String last4 = rs.getString("sender_last4");
                            OffsetDateTime paidAt = rs.getObject("paid_at", OffsetDateTime.class);
                            out.add(new PaymentFacts(rs.getObject("id", UUID.class), rs.getLong("amount_minor"), rs.getString("currency").strip(),
                                    rs.getBoolean("has_tx"), rs.getString("reference"), rs.getString("sender_name"),
                                    rs.getString("sender_phone_hash"), last4 == null ? null : last4.strip(),
                                    paidAt == null ? null : paidAt.toInstant()));
                        }
                        return out;
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read open payments", e);
        }
    }

    private static Stored read(ResultSet rs) throws SQLException {
        long amount = rs.getLong("amount_minor");
        Long amountMinor = rs.wasNull() ? null : amount;   // asked at once: wasNull refers to the last column read
        OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
        return new Stored(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getString("state"), amountMinor, rs.getLong("excess_minor"), rs.getInt("score"), rs.getString("reasons_json"),
                rs.getObject("ledger_txn_id", UUID.class), rs.getObject("decided_by", UUID.class),
                decidedAt == null ? null : decidedAt.toInstant());
    }
}
