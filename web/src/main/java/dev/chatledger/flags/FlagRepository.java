package dev.chatledger.flags;

import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.PaymentContext;
import dev.chatledger.suspicion.SenderRecord;
import dev.chatledger.tenancy.DbTime;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes and reads suspicion flags on a caller's connection, so a flag always commits or rolls back with the work that found it (the payment
 * or order it is about, or the matcher's decision). Every statement names the tenant and is a complete literal; the database adds row-level
 * security, typed foreign keys to the same tenant, the shape CHECKs and the write-once trigger (V019).
 *
 * <p>Only fixed text is stored: the reason is the code's sentence ({@link dev.chatledger.suspicion.FlagCode#sentence}), never anything from a
 * message.
 */
@Repository
public class FlagRepository {

    /** Whether a pair is flagged, by scope (task 7, D3): any flag, open or resolved, counts. */
    public record FlagState(boolean payment, boolean order, boolean pair) {
        public boolean any() {
            return payment || order || pair;
        }
    }

    /**
     * One flag as the resolve step sees it: the page it belongs to (its payment, or its order). Whether it is still open is decided by the
     * UPDATE itself ({@code resolved_at IS NULL}), with the write-once trigger behind it.
     */
    public record Stored(UUID id, String subjectType, UUID paymentId, UUID orderId) {
    }

    /** Stores a finding unless that rule already flagged that subject. Returns true if a row was written. */
    public boolean insertWithin(Connection c, UUID tenantId, Finding f, Instant at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO flag(id, tenant_id, subject_type, payment_id, order_id, code, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT DO NOTHING")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenantId);
            ps.setString(3, f.subject().name());
            setUuid(ps, 4, f.paymentId());
            setUuid(ps, 5, f.orderId());
            ps.setString(6, f.code().name());
            ps.setString(7, f.code().sentence());
            ps.setObject(8, DbTime.utc(at));
            return ps.executeUpdate() == 1;
        }
    }

    public int insertAllWithin(Connection c, UUID tenantId, List<Finding> findings, Instant at) throws SQLException {
        int written = 0;
        for (Finding f : findings) {
            if (insertWithin(c, tenantId, f, at)) {
                written++;
            }
        }
        return written;
    }

    /**
     * The flag state of one pair: a payment flag that names no order, an order flag, a flag on exactly this pair. A flag on the same
     * payment with another order is not this pair's.
     */
    public FlagState stateWithin(Connection c, UUID tenantId, UUID paymentId, UUID orderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM flag WHERE tenant_id = ? AND payment_id = ? AND order_id IS NULL), "
                        + "EXISTS (SELECT 1 FROM flag WHERE tenant_id = ? AND subject_type = 'ORDER' AND order_id = ?), "
                        + "EXISTS (SELECT 1 FROM flag WHERE tenant_id = ? AND payment_id = ? AND order_id = ?)")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, paymentId);
            ps.setObject(3, tenantId);
            ps.setObject(4, orderId);
            ps.setObject(5, tenantId);
            ps.setObject(6, paymentId);
            ps.setObject(7, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new FlagState(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3));
            }
        }
    }

    /**
     * The earlier payments from one sender phone, paid at or before {@code paidAt}, most recent first, at most
     * {@link PaymentContext#MAX_SENDER_RECORDS}; repeats ({@code DUPLICATE}) and this payment left out. For R6 and R10.
     */
    public List<SenderRecord> senderRecordsWithin(Connection c, UUID tenantId, String phoneHash, UUID paymentId, Instant paidAt) throws SQLException {
        // Payments from one phone are recorded one after the other: without this, sixteen sent at once would each see only the committed ones
        // and a burst could slip under R6 (READ COMMITTED). A transaction-scoped advisory lock on (tenant, phone hash) serialises them, and
        // only them; it is released at commit or rollback. Found by MatchingConcurrencyIT.
        try (PreparedStatement lock = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ?, 0))")) {
            lock.setString(1, tenantId.toString());
            lock.setString(2, phoneHash);
            lock.execute();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT sender_name, paid_at FROM payment WHERE tenant_id = ? AND sender_phone_hash = ? AND id <> ? AND paid_at <= ? "
                        + "AND status NOT IN ('DUPLICATE', 'UNPARSED') ORDER BY paid_at DESC, id LIMIT ?")) {
            ps.setObject(1, tenantId);
            ps.setString(2, phoneHash);
            ps.setObject(3, paymentId);
            ps.setObject(4, DbTime.utc(paidAt));
            ps.setInt(5, PaymentContext.MAX_SENDER_RECORDS);
            List<SenderRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SenderRecord(rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant()));
                }
            }
            return List.copyOf(out);
        }
    }

    /** The flag, locked, or empty if it is not this tenant's. */
    public Optional<Stored> lockWithin(Connection c, UUID tenantId, UUID flagId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, subject_type, payment_id, order_id FROM flag WHERE tenant_id = ? AND id = ? FOR NO KEY UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, flagId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Stored(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class))) : Optional.empty();
            }
        }
    }

    /** Resolves an open flag. Returns the rows changed: 1, or 0 if it is not this tenant's or already resolved. */
    public int resolveWithin(Connection c, UUID tenantId, UUID flagId, UUID actor, Instant at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE flag SET resolved_at = ?, resolved_by = ? WHERE tenant_id = ? AND id = ? AND resolved_at IS NULL")) {
            ps.setObject(1, DbTime.utc(at));
            ps.setObject(2, actor);
            ps.setObject(3, tenantId);
            ps.setObject(4, flagId);
            return ps.executeUpdate();
        }
    }

    private static void setUuid(PreparedStatement ps, int index, UUID value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.OTHER);
        } else {
            ps.setObject(index, value);
        }
    }
}
