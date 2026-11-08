package dev.chatledger.orders;

import dev.chatledger.ingestion.OrderDecider;
import dev.chatledger.ledger.LedgerException;
import dev.chatledger.ledger.jdbc.JdbcLedgerRepository;
import dev.chatledger.matching.ChartOfAccounts;
import dev.chatledger.matching.Postings;
import dev.chatledger.security.AuditLogRepository;
import dev.chatledger.tenancy.TenantScope;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores and reads orders. Every call runs in a {@link TenantScope}, so row-level security applies, and every
 * statement names the tenant as well. Every statement is a complete string literal with bound parameters: nothing
 * from a message is ever part of the SQL text.
 *
 * <p>{@link #insert} writes the customer, the order, its lines and the audit row in one transaction. If the
 * {@code (tenant, external id)} pair already exists nothing is written at all (not even a customer).
 */
@Repository
public class OrderRepository {

    /** What to store. All text fields have been validated; they are bound as parameters. */
    public record NewOrder(String externalId, String orderRef, String rawText, OrderDecider.Draft draft,
                           String customerName, String phoneHash, String phoneLast4, String deliveryNote, UUID actor) {
        @Override
        public String toString() {
            return "NewOrder[status=" + draft.status() + "]";
        }
    }

    /** An order as stored. {@code toString} prints ids and the status only. */
    public record Stored(UUID id, String externalId, String orderRef, UUID customerId, Long totalMinor, String currency,
                         String status, String rawText, String deliveryNote) {
        @Override
        public String toString() {
            return "Order[id=" + id + ", status=" + status + "]";
        }
    }

    /** A stored line; {@code unitMinor} is null when no price was known. */
    public record StoredLine(String label, int qty, Long unitMinor) {
        @Override
        public String toString() {
            return "Line[qty=" + qty + "]";
        }
    }

    /** Thrown inside the transaction to roll it back when the order already exists. */
    private static final class AlreadyExists extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AlreadyExists() {
            super("already exists", null, false, false);
        }
    }

    /** The display name of a customer known only by phone. The matcher never treats it as a name (see NameSimilarity.NEUTRAL_NAME). */
    public static final String UNKNOWN_CUSTOMER = "Unknown customer";

    private final TenantScope scope;
    private final JdbcLedgerRepository ledger;

    public OrderRepository(DataSource dataSource) {
        this.scope = new TenantScope(dataSource);
        this.ledger = new JdbcLedgerRepository(scope);
    }

    /** The new order's id, or empty if this tenant already has an order with that external id (nothing was written). */
    public Optional<UUID> insert(UUID tenantId, NewOrder order) {
        try {
            return Optional.of(scope.call(tenantId, c -> insertWithin(c, tenantId, order)));
        } catch (AlreadyExists exists) {
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store an order", e);
        } catch (LedgerException e) {
            // The posting failed, so the whole transaction rolled back: the order is not stored. The caller gets this exception, and a retry
            // with the same external id starts from nothing (and cannot post twice: one posting per order is a unique index).
            throw new IllegalStateException("Could not post an order to the ledger", e);
        }
    }

    private UUID insertWithin(Connection c, UUID tenantId, NewOrder order) throws SQLException {
        UUID customerId = customer(c, tenantId, order);
        UUID orderId = UUID.randomUUID();
        OrderDecider.Draft draft = order.draft();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO orders(id, tenant_id, external_id, order_ref, customer_id, total_minor, currency, status, raw_text, "
                        + "delivery_note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, external_id) DO NOTHING RETURNING id")) {
            ps.setObject(1, orderId);
            ps.setObject(2, tenantId);
            ps.setString(3, order.externalId());
            ps.setString(4, order.orderRef());
            ps.setObject(5, customerId);
            if (draft.totalMinor() == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, draft.totalMinor());
            }
            ps.setString(7, draft.currency());
            ps.setString(8, draft.status().name());
            ps.setString(9, order.rawText());
            ps.setString(10, order.deliveryNote());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AlreadyExists();
                }
            }
        }
        if (!draft.lines().isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO order_line(id, order_id, tenant_id, label, qty, unit_minor) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (OrderDecider.Line line : draft.lines()) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, orderId);
                    ps.setObject(3, tenantId);
                    ps.setString(4, line.label());
                    ps.setInt(5, line.qty());
                    if (line.unitMinor() == null) {
                        ps.setNull(6, Types.BIGINT);
                    } else {
                        ps.setLong(6, line.unitMinor());
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        if (draft.status() == OrderDecider.Status.PENDING_PAYMENT) {
            // The order is a receivable from this moment (requirement 4.1): debit Accounts receivable, credit Sales revenue. In the same
            // transaction as the order, so an order without its posting, or a posting without its order, cannot exist.
            ChartOfAccounts.Accounts accounts = ChartOfAccounts.ensure(c, tenantId);
            ledger.appendWithin(c, Postings.orderAccepted(tenantId, orderId, draft.totalMinor(), accounts, java.time.Instant.now()));
        }
        AuditLogRepository.appendWithin(c, tenantId, order.actor(), "ORDER_INGESTED", orderId.toString());
        return orderId;
    }

    /**
     * A customer with a phone is found or created by the phone's keyed hash (unique per tenant). A customer with only a
     * name gets a row of their own: without a phone there is no safe key to recognise them by.
     */
    private UUID customer(Connection c, UUID tenantId, NewOrder order) throws SQLException {
        if (order.phoneHash() != null) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO customer(id, tenant_id, display_name, phone_hash, phone_last4) VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT (tenant_id, phone_hash) DO NOTHING")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, tenantId);
                ps.setString(3, order.customerName() == null ? UNKNOWN_CUSTOMER : order.customerName());
                ps.setString(4, order.phoneHash());
                ps.setString(5, order.phoneLast4());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM customer WHERE tenant_id = ? AND phone_hash = ?")) {
                ps.setObject(1, tenantId);
                ps.setString(2, order.phoneHash());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getObject(1, UUID.class);
                }
            }
        }
        if (order.customerName() != null) {
            UUID id = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO customer(id, tenant_id, display_name) VALUES (?, ?, ?)")) {
                ps.setObject(1, id);
                ps.setObject(2, tenantId);
                ps.setString(3, order.customerName());
                ps.executeUpdate();
            }
            return id;
        }
        return null;
    }

    public Optional<Stored> findByExternalId(UUID tenantId, String externalId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, external_id, order_ref, customer_id, total_minor, currency, status, raw_text, delivery_note "
                                + "FROM orders WHERE tenant_id = ? AND external_id = ?")) {
                    ps.setObject(1, tenantId);
                    ps.setString(2, externalId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return Optional.<Stored>empty();
                        }
                        UUID id = rs.getObject(1, UUID.class);
                        String externalIdFound = rs.getString(2);
                        String orderRef = rs.getString(3);
                        UUID customerId = rs.getObject(4, UUID.class);
                        Long total = nullableLong(rs, 5);   // read and test for NULL straight away, before any other column
                        return Optional.of(new Stored(id, externalIdFound, orderRef, customerId, total, rs.getString(6).strip(),
                                rs.getString(7), rs.getString(8), rs.getString(9)));
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read an order", e);
        }
    }

    public List<StoredLine> lines(UUID tenantId, UUID orderId) {
        try {
            return scope.call(tenantId, c -> {
                List<StoredLine> lines = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT label, qty, unit_minor FROM order_line WHERE tenant_id = ? AND order_id = ? ORDER BY label, qty")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String label = rs.getString(1);
                            int qty = rs.getInt(2);
                            lines.add(new StoredLine(label, qty, nullableLong(rs, 3)));
                        }
                    }
                }
                return lines;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read order lines", e);
        }
    }

    public long count(UUID tenantId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM orders WHERE tenant_id = ?")) {
                    ps.setObject(1, tenantId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count orders", e);
        }
    }

    /** A BIGINT column that may be NULL. {@code wasNull} refers to the last column read, so it is asked right here. */
    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
