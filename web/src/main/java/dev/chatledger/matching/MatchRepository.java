package dev.chatledger.matching;

import dev.chatledger.tenancy.TenantScope;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    /**
     * The orders the seller has said no to for this payment: a suggestion they rejected, or a match they reversed. The engine never
     * proposes or applies these pairs again; the seller can still match one by hand. A suggestion retired by the system (no decider) is
     * not a decision of the seller and is not listed.
     */
    public Set<UUID> declinedOrders(UUID tenantId, UUID paymentId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT order_id FROM match WHERE tenant_id = ? AND payment_id = ? AND state IN ('REJECTED', 'REVERSED') AND decided_by IS NOT NULL")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, paymentId);
                    return readIds(ps);
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read matches", e);
        }
    }

    /** The payments the seller has said no to for this order (see {@link #declinedOrders}). */
    public Set<UUID> declinedPayments(UUID tenantId, UUID orderId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT payment_id FROM match WHERE tenant_id = ? AND order_id = ? AND state IN ('REJECTED', 'REVERSED') AND decided_by IS NOT NULL")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, orderId);
                    return readIds(ps);
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read matches", e);
        }
    }

    private static Set<UUID> readIds(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            Set<UUID> out = new HashSet<>();
            while (rs.next()) {
                out.add(rs.getObject(1, UUID.class));
            }
            return Set.copyOf(out);
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
     * {@code PARTIALLY_PAID} (which always have a total), something left to pay. Parsed fields only. {@code referenceShared} is true when
     * another open order has the same reference key.
     */
    public List<OrderFacts> openOrders(UUID tenantId, String currency, int limit) {
        return orders(tenantId, currency, null, limit);
    }

    /** One open order of the tenant (the same rules as {@link #openOrders}), or empty if it is not open. */
    public Optional<OrderFacts> openOrder(UUID tenantId, UUID orderId) {
        List<OrderFacts> rows = orders(tenantId, null, orderId, 1);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private List<OrderFacts> orders(UUID tenantId, String currency, UUID orderId, int limit) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT x.id, x.total_minor, x.applied, x.currency, x.created_at, x.order_ref, cu.display_name, cu.phone_hash, cu.phone_last4, x.ref_n "
                                + "FROM (SELECT o.id, o.tenant_id, o.total_minor, o.currency, o.created_at, o.order_ref, o.customer_id, "
                                + "COALESCE((SELECT SUM(m.amount_minor) FROM match m WHERE m.tenant_id = o.tenant_id AND m.order_id = o.id "
                                + "AND m.state IN ('AUTO', 'CONFIRMED')), 0) AS applied, "
                                + "CASE WHEN o.order_ref IS NULL THEN 1 ELSE COUNT(*) OVER (PARTITION BY upper(o.order_ref)) END AS ref_n "
                                + "FROM orders o WHERE o.tenant_id = ? AND o.status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID') "
                                + "AND (CAST(? AS CHAR(3)) IS NULL OR o.currency = ?)) x "
                                + "LEFT JOIN customer cu ON cu.id = x.customer_id AND cu.tenant_id = x.tenant_id "
                                + "WHERE x.applied < x.total_minor AND (CAST(? AS UUID) IS NULL OR x.id = ?) "
                                + "ORDER BY x.created_at DESC, x.id LIMIT ?")) {
                    ps.setObject(1, tenantId);
                    ps.setString(2, currency);
                    ps.setString(3, currency);
                    setUuid(ps, 4, orderId);
                    setUuid(ps, 5, orderId);
                    ps.setInt(6, limit);
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

    /** A free payment with who recorded it, as scoring needs them. */
    public record PaymentCandidate(PaymentFacts facts, Recorder recorder) {
        @Override
        public String toString() {
            return "PaymentCandidate[" + facts.id() + ", " + recorder + "]";
        }
    }

    /** The tenant's payments free to be matched ({@code RECEIVED} or {@code UNVERIFIED}) in {@code currency}, most recent first, at most {@code limit}. */
    public List<PaymentFacts> openPayments(UUID tenantId, String currency, int limit) {
        return openPaymentCandidates(tenantId, currency, limit).stream().map(PaymentCandidate::facts).toList();
    }

    /**
     * The same, with the {@link Recorder} of each: taken from the append-only audit trail of the payment's ingestion, never from a caller.
     * If there were ever two such entries the earliest counts, so a later entry cannot raise the trust of a payment.
     */
    public List<PaymentCandidate> openPaymentCandidates(UUID tenantId, String currency, int limit) {
        return payments(tenantId, currency, null, limit);
    }

    /** One free payment of the tenant with its recorder, or empty if it is not free to be matched. */
    public Optional<PaymentCandidate> paymentCandidate(UUID tenantId, UUID paymentId) {
        List<PaymentCandidate> rows = payments(tenantId, null, paymentId, 1);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private List<PaymentCandidate> payments(UUID tenantId, String currency, UUID paymentId, int limit) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT p.id, p.amount_minor, p.currency, p.tx_id IS NOT NULL AS has_tx, p.reference, p.sender_name, p.sender_phone_hash, "
                                + "p.sender_last4, p.paid_at, COALESCE((SELECT u.role FROM audit_log a "
                                + "LEFT JOIN app_user u ON u.id = a.actor AND u.tenant_id = a.tenant_id WHERE a.tenant_id = p.tenant_id "
                                + "AND a.action = 'PAYMENT_INGESTED' AND a.subject = p.id::text ORDER BY a.at, a.id LIMIT 1), 'HELPER') AS recorded_by "
                                + "FROM payment p WHERE p.tenant_id = ? AND p.status IN ('RECEIVED', 'UNVERIFIED') "
                                + "AND (CAST(? AS CHAR(3)) IS NULL OR p.currency = ?) AND (CAST(? AS UUID) IS NULL OR p.id = ?) "
                                + "ORDER BY p.created_at DESC, p.id LIMIT ?")) {
                    ps.setObject(1, tenantId);
                    ps.setString(2, currency);
                    ps.setString(3, currency);
                    setUuid(ps, 4, paymentId);
                    setUuid(ps, 5, paymentId);
                    ps.setInt(6, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<PaymentCandidate> out = new ArrayList<>();
                        while (rs.next()) {
                            String last4 = rs.getString("sender_last4");
                            OffsetDateTime paidAt = rs.getObject("paid_at", OffsetDateTime.class);
                            PaymentFacts facts = new PaymentFacts(rs.getObject("id", UUID.class), rs.getLong("amount_minor"), rs.getString("currency").strip(),
                                    rs.getBoolean("has_tx"), rs.getString("reference"), rs.getString("sender_name"),
                                    rs.getString("sender_phone_hash"), last4 == null ? null : last4.strip(),
                                    paidAt == null ? null : paidAt.toInstant());
                            out.add(new PaymentCandidate(facts, recorderOf(rs.getString("recorded_by"))));
                        }
                        return out;
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read open payments", e);
        }
    }

    /**
     * Only the exact word SELLER is trusted. Anything else is a helper, the least trusted reading, and that includes a missing actor
     * and the word MACHINE: the signed machine feed does not exist before task 11, which will record an explicit machine identity in
     * the audit entry and add its own branch here. Until then no stored value can produce {@link Recorder#MACHINE}.
     */
    static Recorder recorderOf(String stored) {
        return "SELLER".equals(stored) ? Recorder.SELLER : Recorder.HELPER;
    }

    private static void setUuid(PreparedStatement ps, int index, UUID value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.OTHER);
        } else {
            ps.setObject(index, value);
        }
    }

    // ── Inside the caller's transaction (the atomic apply of a match) ───────
    //
    // These run on a connection that is already inside a TenantScope for the tenant, so they commit or roll back with everything else the
    // caller wrote. Each statement names the tenant; each write returns the number of rows it changed so that the caller can refuse a
    // write that touched nothing (a row of another tenant, or a row in the wrong state).

    /** A payment row locked for the transaction, with the fields the apply step needs. */
    public record LockedPayment(UUID id, String status, Long amountMinor, String currency, boolean hasTransactionId, String reference,
                                String senderName, String senderPhoneHash, String senderLast4, Instant paidAt, Recorder recorder) {
        @Override
        public String toString() {
            return "LockedPayment[id=" + id + ", status=" + status + "]";
        }
    }

    /** An order row locked for the transaction, with its customer's fields. */
    public record LockedOrder(UUID id, String status, Long totalMinor, String currency, Instant createdAt, String orderRef,
                              String customerName, String customerPhoneHash, String customerLast4) {
        @Override
        public String toString() {
            return "LockedOrder[id=" + id + ", status=" + status + "]";
        }
    }

    // Every row lock here is FOR NO KEY UPDATE, never FOR UPDATE. Inserting a match takes a "key share" lock on the order and the payment it
    // refers to (PostgreSQL's foreign-key check), and FOR UPDATE waits for a key share: the engine storing a suggestion (key share on the
    // order, then on the payment) could wait for a seller's decision (lock on the payment, then wait for the order) and the reverse, for ever.
    // These locks never change a key column, so the weaker lock is enough, serialises the matcher's own operations exactly as before, and
    // does not wait for a key share (ForeignKeyChecksDoNotBlockIT).

    /**
     * Locks the payment row (FOR NO KEY UPDATE). Always lock the payment first and the order second. The "recorded_by" subselect is the same
     * text as in {@link #payments}: SQL is only ever written as literals here (a source scan enforces it), so it is repeated, and a
     * test reads the recorder through both and compares.
     */
    public Optional<LockedPayment> lockPayment(Connection c, UUID tenantId, UUID paymentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT p.id, p.status, p.amount_minor, p.currency, p.tx_id IS NOT NULL AS has_tx, p.reference, p.sender_name, p.sender_phone_hash, "
                        + "p.sender_last4, p.paid_at, COALESCE((SELECT u.role FROM audit_log a "
                        + "LEFT JOIN app_user u ON u.id = a.actor AND u.tenant_id = a.tenant_id WHERE a.tenant_id = p.tenant_id "
                        + "AND a.action = 'PAYMENT_INGESTED' AND a.subject = p.id::text ORDER BY a.at, a.id LIMIT 1), 'HELPER') AS recorded_by "
                        + "FROM payment p WHERE p.tenant_id = ? AND p.id = ? FOR NO KEY UPDATE OF p")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long amount = rs.getLong("amount_minor");
                Long amountMinor = rs.wasNull() ? null : amount;
                String last4 = rs.getString("sender_last4");
                OffsetDateTime paidAt = rs.getObject("paid_at", OffsetDateTime.class);
                return Optional.of(new LockedPayment(rs.getObject("id", UUID.class), rs.getString("status"), amountMinor,
                        rs.getString("currency").strip(), rs.getBoolean("has_tx"), rs.getString("reference"), rs.getString("sender_name"),
                        rs.getString("sender_phone_hash"), last4 == null ? null : last4.strip(), paidAt == null ? null : paidAt.toInstant(),
                        recorderOf(rs.getString("recorded_by"))));
            }
        }
    }

    /** Locks the order row (FOR NO KEY UPDATE), after the payment's. */
    public Optional<LockedOrder> lockOrder(Connection c, UUID tenantId, UUID orderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT o.id, o.status, o.total_minor, o.currency, o.created_at, o.order_ref, cu.display_name, cu.phone_hash, cu.phone_last4 "
                        + "FROM orders o LEFT JOIN customer cu ON cu.id = o.customer_id AND cu.tenant_id = o.tenant_id "
                        + "WHERE o.tenant_id = ? AND o.id = ? FOR NO KEY UPDATE OF o")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long total = rs.getLong("total_minor");
                Long totalMinor = rs.wasNull() ? null : total;
                String last4 = rs.getString("phone_last4");
                return Optional.of(new LockedOrder(rs.getObject("id", UUID.class), rs.getString("status"), totalMinor,
                        rs.getString("currency").strip(), rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getString("order_ref"),
                        rs.getString("display_name"), rs.getString("phone_hash"), last4 == null ? null : last4.strip()));
            }
        }
    }

    /** The sum of the order's active matches, inside the transaction. */
    public long appliedWithin(Connection c, UUID tenantId, UUID orderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM match WHERE tenant_id = ? AND order_id = ? AND state IN ('AUTO', 'CONFIRMED')")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** True if another open order of the tenant has the same reference key as this reference. */
    public boolean referenceSharedWithin(Connection c, UUID tenantId, String orderRef) throws SQLException {
        if (orderRef == null) {
            return false;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM orders WHERE tenant_id = ? AND upper(order_ref) = upper(?) AND status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID')")) {
            ps.setObject(1, tenantId);
            ps.setString(2, orderRef);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 1;
            }
        }
    }

    /**
     * The id of the open suggestion for this pair, if there is one, with its row locked (after the payment's and the order's). A seller
     * who rejects it at the same moment is therefore either seen (no suggestion any more) or waited for, never overtaken half way.
     */
    public Optional<UUID> suggestionWithin(Connection c, UUID tenantId, UUID orderId, UUID paymentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM match WHERE tenant_id = ? AND order_id = ? AND payment_id = ? AND state = 'SUGGESTED' FOR NO KEY UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            ps.setObject(3, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.<UUID>empty();
            }
        }
    }

    /** True if the order's {@code ORDER_ACCEPTED} posting exists. */
    public boolean orderPostedWithin(Connection c, UUID tenantId, UUID orderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM ledger_txn WHERE tenant_id = ? AND kind = 'ORDER_ACCEPTED' AND ref_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** What to store for an active match. {@code decidedBy} is null for an automatic one. */
    public record NewActive(UUID id, UUID orderId, UUID paymentId, String state, long amountMinor, long excessMinor, int score,
                            String reasonsJson, UUID ledgerTxnId, UUID decidedBy, Instant decidedAt) {
        @Override
        public String toString() {
            return "NewActive[id=" + id + ", state=" + state + "]";
        }
    }

    public int insertActiveWithin(Connection c, UUID tenantId, NewActive m) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO match(id, tenant_id, order_id, payment_id, amount_minor, excess_minor, score, reasons_json, state, ledger_txn_id, "
                        + "decided_by, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, m.id());
            ps.setObject(2, tenantId);
            ps.setObject(3, m.orderId());
            ps.setObject(4, m.paymentId());
            ps.setLong(5, m.amountMinor());
            ps.setLong(6, m.excessMinor());
            ps.setInt(7, m.score());
            ps.setString(8, m.reasonsJson());
            ps.setString(9, m.state());
            ps.setObject(10, m.ledgerTxnId());
            setUuid(ps, 11, m.decidedBy());
            ps.setObject(12, m.decidedAt() == null ? null : m.decidedAt().atOffset(java.time.ZoneOffset.UTC));
            return ps.executeUpdate();
        }
    }

    /** Turns a suggestion into a confirmed match with its amounts (the one move that may set them). */
    public int confirmSuggestionWithin(Connection c, UUID tenantId, UUID matchId, UUID decidedBy, Instant decidedAt, long amountMinor,
                                       long excessMinor, UUID ledgerTxnId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE match SET state = 'CONFIRMED', decided_by = ?, decided_at = ?, amount_minor = ?, excess_minor = ?, ledger_txn_id = ? "
                        + "WHERE tenant_id = ? AND id = ? AND state = 'SUGGESTED'")) {
            ps.setObject(1, decidedBy);
            ps.setObject(2, decidedAt.atOffset(java.time.ZoneOffset.UTC));
            ps.setLong(3, amountMinor);
            ps.setLong(4, excessMinor);
            ps.setObject(5, ledgerTxnId);
            ps.setObject(6, tenantId);
            ps.setObject(7, matchId);
            return ps.executeUpdate();
        }
    }

    /** The system retires one open suggestion (no decider): it has been overtaken. */
    public int supersedeWithin(Connection c, UUID tenantId, UUID matchId, Instant at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE match SET state = 'REJECTED', decided_at = ? WHERE tenant_id = ? AND id = ? AND state = 'SUGGESTED'")) {
            ps.setObject(1, at.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(2, tenantId);
            ps.setObject(3, matchId);
            return ps.executeUpdate();
        }
    }

    /** The system retires every other open suggestion for this payment: it is matched now. */
    public int supersedeForPaymentWithin(Connection c, UUID tenantId, UUID paymentId, UUID exceptMatchId, Instant at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE match SET state = 'REJECTED', decided_at = ? WHERE tenant_id = ? AND payment_id = ? AND state = 'SUGGESTED' AND id <> ?")) {
            ps.setObject(1, at.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(2, tenantId);
            ps.setObject(3, paymentId);
            ps.setObject(4, exceptMatchId);
            return ps.executeUpdate();
        }
    }

    /** The system retires every other open suggestion for this order: it is paid in full. */
    public int supersedeForOrderWithin(Connection c, UUID tenantId, UUID orderId, UUID exceptMatchId, Instant at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE match SET state = 'REJECTED', decided_at = ? WHERE tenant_id = ? AND order_id = ? AND state = 'SUGGESTED' AND id <> ?")) {
            ps.setObject(1, at.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(2, tenantId);
            ps.setObject(3, orderId);
            ps.setObject(4, exceptMatchId);
            return ps.executeUpdate();
        }
    }

    public int setOrderStatusWithin(Connection c, UUID tenantId, UUID orderId, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE orders SET status = ? WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, status);
            ps.setObject(2, tenantId);
            ps.setObject(3, orderId);
            return ps.executeUpdate();
        }
    }

    public int setPaymentStatusWithin(Connection c, UUID tenantId, UUID paymentId, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE payment SET status = ? WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, status);
            ps.setObject(2, tenantId);
            ps.setObject(3, paymentId);
            return ps.executeUpdate();
        }
    }

    // ── The seller's actions (inside the caller's transaction) ──────────────

    /** A match row as the seller's actions need it. {@code toString} prints the id and the state only. */
    public record MatchRow(UUID id, UUID orderId, UUID paymentId, String state, UUID ledgerTxnId) {
        @Override
        public String toString() {
            return "MatchRow[id=" + id + ", state=" + state + "]";
        }
    }

    private static Optional<MatchRow> readMatchRow(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next()
                    ? Optional.of(new MatchRow(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("payment_id", UUID.class),
                            rs.getString("state"), rs.getObject("ledger_txn_id", UUID.class)))
                    : Optional.<MatchRow>empty();
        }
    }

    /**
     * A match, without a lock: used to learn which payment and order to lock first (they never change). The state read here is only a
     * hint; the caller must read it again with {@link #lockMatchWithin} once it holds the payment and order locks.
     */
    public Optional<MatchRow> matchWithin(Connection c, UUID tenantId, UUID matchId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, order_id, payment_id, state, ledger_txn_id FROM match WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, matchId);
            return readMatchRow(ps);
        }
    }

    /** Locks a match row (FOR NO KEY UPDATE) after the payment's and the order's, and reads its current state. */
    public Optional<MatchRow> lockMatchWithin(Connection c, UUID tenantId, UUID matchId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, order_id, payment_id, state, ledger_txn_id FROM match WHERE tenant_id = ? AND id = ? FOR NO KEY UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, matchId);
            return readMatchRow(ps);
        }
    }

    /**
     * True if the user is an enabled SELLER of this tenant, read now from the user table. A seller's action trusts no role passed in by
     * a caller: whoever calls the service, the database says who may decide.
     */
    public boolean isSellerWithin(Connection c, UUID tenantId, UUID userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM app_user WHERE tenant_id = ? AND id = ? AND role = 'SELLER' AND disabled_at IS NULL")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** True if the seller said no to this pair before (see {@link #declinedOrders}). */
    public boolean wasDeclinedWithin(Connection c, UUID tenantId, UUID orderId, UUID paymentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM match WHERE tenant_id = ? AND order_id = ? AND payment_id = ? AND state IN ('REJECTED', 'REVERSED') AND decided_by IS NOT NULL")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            ps.setObject(3, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** The seller rejects one open suggestion. */
    public int rejectWithin(Connection c, UUID tenantId, UUID matchId, UUID decidedBy, Instant at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE match SET state = 'REJECTED', decided_by = ?, decided_at = ? WHERE tenant_id = ? AND id = ? AND state = 'SUGGESTED'")) {
            ps.setObject(1, decidedBy);
            ps.setObject(2, at.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(3, tenantId);
            ps.setObject(4, matchId);
            return ps.executeUpdate();
        }
    }

    /** The seller says a payment belongs to no order: every open suggestion for it is rejected, by them. */
    public int rejectOpenForPaymentWithin(Connection c, UUID tenantId, UUID paymentId, UUID decidedBy, Instant at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE match SET state = 'REJECTED', decided_by = ?, decided_at = ? WHERE tenant_id = ? AND payment_id = ? AND state = 'SUGGESTED'")) {
            ps.setObject(1, decidedBy);
            ps.setObject(2, at.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(3, tenantId);
            ps.setObject(4, paymentId);
            return ps.executeUpdate();
        }
    }

    /** The seller reverses an active match (the caller has already posted the reversal of its posting). */
    public int reverseMatchWithin(Connection c, UUID tenantId, UUID matchId, UUID decidedBy, Instant at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE match SET state = 'REVERSED', decided_by = ?, decided_at = ? WHERE tenant_id = ? AND id = ? AND state IN ('AUTO', 'CONFIRMED')")) {
            ps.setObject(1, decidedBy);
            ps.setObject(2, at.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(3, tenantId);
            ps.setObject(4, matchId);
            return ps.executeUpdate();
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
