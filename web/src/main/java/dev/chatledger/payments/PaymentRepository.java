package dev.chatledger.payments;

import dev.chatledger.security.AuditLogRepository;
import dev.chatledger.tenancy.DbTime;
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
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores and reads payments. Every call runs in a {@link TenantScope}, so row-level security applies, and every statement names
 * the tenant as well. Every statement is a complete string literal with bound parameters: nothing from a message is ever part
 * of the SQL text.
 *
 * <p>{@link #insert} writes the payment and its audit row in one transaction. If another row already holds the same external id,
 * or the same transaction id as a non-duplicate payment, nothing is written and the caller decides what that means.
 */
@Repository
public class PaymentRepository {

    /** What to store. Text fields have been validated; they are bound as parameters. */
    public record NewPayment(String externalId, String status, String templateId, String provider, String transactionId,
                             Long amountMinor, String currency, String senderName, String senderPhoneHash, String senderLast4,
                             String reference, Instant paidAt, String rawText, UUID duplicateOf, UUID actor) {
        @Override
        public String toString() {
            return "NewPayment[status=" + status + "]";
        }
    }

    /** A payment as stored. {@code toString} prints ids and the status only. */
    public record Stored(UUID id, String externalId, String status, String templateId, String provider, String transactionId,
                         Long amountMinor, String currency, String senderName, String senderPhoneHash, String senderLast4,
                         String reference, Instant paidAt, UUID duplicateOf, String rawText) {
        @Override
        public String toString() {
            return "Payment[id=" + id + ", status=" + status + "]";
        }
    }

    private static final class Conflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Conflict() {
            super("conflict", null, false, false);
        }
    }

    private final TenantScope scope;

    public PaymentRepository(DataSource dataSource) {
        this.scope = new TenantScope(dataSource);
    }

    /** The new payment's id, or empty if a unique key already holds the row (nothing was written). */
    /** Work that must commit with the new payment or not at all (its suspicion flags). It runs on the insert's connection, after the insert. */
    @FunctionalInterface
    public interface AfterInsert {
        void after(Connection c, UUID paymentId) throws SQLException;
    }

    /**
     * Stores the payment, its audit row and whatever {@code after} writes, in one transaction. Empty if the external id was already used
     * (nothing is written, and {@code after} does not run). If {@code after} fails, the payment is not stored either.
     */
    public Optional<UUID> insert(UUID tenantId, NewPayment p, AfterInsert after) {
        java.util.Objects.requireNonNull(after, "after");
        try {
            return Optional.of(scope.call(tenantId, c -> {
                UUID id = insertWithin(c, tenantId, p);
                after.after(c, id);
                return id;
            }));
        } catch (Conflict conflict) {
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store a payment", e);
        }
    }

    private UUID insertWithin(Connection c, UUID tenantId, NewPayment p) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO payment(id, tenant_id, external_id, provider, template_id, tx_id, amount_minor, currency, sender_name, "
                        + "sender_phone_hash, sender_last4, reference, paid_at, status, raw_text, duplicate_of) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setString(3, p.externalId());
            ps.setString(4, p.provider());
            ps.setString(5, p.templateId());
            ps.setString(6, p.transactionId());
            if (p.amountMinor() == null) {
                ps.setNull(7, Types.BIGINT);
            } else {
                ps.setLong(7, p.amountMinor());
            }
            ps.setString(8, p.currency());
            ps.setString(9, p.senderName());
            ps.setString(10, p.senderPhoneHash());
            ps.setString(11, p.senderLast4());
            ps.setString(12, p.reference());
            ps.setObject(13, p.paidAt() == null ? null : DbTime.utc(p.paidAt()));
            ps.setString(14, p.status());
            ps.setString(15, p.rawText());
            ps.setObject(16, p.duplicateOf());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new Conflict();
                }
            }
        }
        AuditLogRepository.appendWithin(c, tenantId, p.actor(), "PAYMENT_INGESTED", id.toString());
        return id;
    }

    public Optional<Stored> findByExternalId(UUID tenantId, String externalId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, external_id, status, template_id, provider, tx_id, amount_minor, currency, sender_name, "
                                + "sender_phone_hash, sender_last4, reference, paid_at, duplicate_of, raw_text "
                                + "FROM payment WHERE tenant_id = ? AND external_id = ?")) {
                    ps.setObject(1, tenantId);
                    ps.setString(2, externalId);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(read(rs)) : Optional.<Stored>empty();
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read a payment", e);
        }
    }

    /** The first (non-duplicate) payment of the tenant with this provider and transaction id, compared in upper case (as the unique index is). */
    public Optional<UUID> findOriginal(UUID tenantId, String provider, String transactionId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id FROM payment WHERE tenant_id = ? AND provider = ? AND upper(tx_id) = upper(?) AND status <> 'DUPLICATE'")) {
                    ps.setObject(1, tenantId);
                    ps.setString(2, provider);
                    ps.setString(3, transactionId);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.<UUID>empty();
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read a payment", e);
        }
    }

    public long count(UUID tenantId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM payment WHERE tenant_id = ?")) {
                    ps.setObject(1, tenantId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count payments", e);
        }
    }

    private static Stored read(ResultSet rs) throws SQLException {
        UUID id = rs.getObject(1, UUID.class);
        String externalId = rs.getString(2);
        String status = rs.getString(3);
        String templateId = rs.getString(4);
        String provider = rs.getString(5);
        String txId = rs.getString(6);
        Long amount = nullableLong(rs, 7);
        String currency = rs.getString(8).strip();
        String name = rs.getString(9);
        String hash = rs.getString(10);
        String last4 = rs.getString(11);
        String reference = rs.getString(12);
        OffsetDateTime paid = rs.getObject(13, OffsetDateTime.class);
        UUID duplicateOf = rs.getObject(14, UUID.class);
        String raw = rs.getString(15);
        return new Stored(id, externalId, status, templateId, provider, txId, amount, currency, name, hash,
                last4 == null ? null : last4.strip(), reference, paid == null ? null : paid.toInstant(), duplicateOf, raw);
    }

    /** A BIGINT column that may be NULL. {@code wasNull} refers to the last column read, so it is asked right here. */
    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
