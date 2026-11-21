package dev.chatledger.orders;

import dev.chatledger.ledger.LedgerException;
import dev.chatledger.ledger.jdbc.JdbcLedgerRepository;
import dev.chatledger.matching.MatchRepository;
import dev.chatledger.security.AuditLogRepository;
import dev.chatledger.tenancy.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The seller cancels an order or dismisses a record (task 12b, D8, D10, D12). Each is one database transaction that first asks the
 * database whether the user is an enabled seller of the tenant, then locks the record and checks it again under the lock.
 *
 * <p><b>Cancel</b>, in order: lock the order; require {@code PENDING_PAYMENT} and nothing applied; reverse its live {@code ORDER_ACCEPTED}
 * posting (a new transaction that mirrors it; the original is never changed); set {@code CANCELLED}; reject its open suggestions on the
 * seller's behalf; append the audit row {@code ORDER_CANCELLED}. The database refuses the move with an active match, and the commit if
 * the posting is still live or an acceptance was reversed on an order that is not cancelled (V021). A partly paid or paid order is
 * unmatched first, so there is never a money posting to split.
 *
 * <p><b>Dismiss</b>: an {@code UNPARSED} order or payment, or a {@code NEEDS_REVIEW} order, gets a row in the append-only
 * {@code record_dismissal} and leaves the board. None of them has a posting or a match, so nothing is reversed. A dismissed order can
 * no longer be made payable (the database refuses it). There is no undo of either action in this task (D12).
 *
 * <p>Locks: only the order (or the payment) and then its suggestion rows, the order every action in the application uses; a cancel never
 * takes a payment lock, so it cannot deadlock with a match, which locks the payment, then the order.
 */
@Service
public class RecordActionsService {

    private static final Logger LOG = LoggerFactory.getLogger(RecordActionsService.class);

    public enum Outcome {
        DONE, NOT_A_SELLER, NOT_FOUND,
        /** Cancel: a payment is applied to the order (partly paid or paid). Unmatch it first. */
        HAS_PAYMENT,
        /** Cancel: the order was never payable (NEEDS_REVIEW or UNPARSED): dismiss it instead. */
        NOT_PAYABLE,
        ALREADY_CANCELLED,
        /** Dismiss: the record reached the books (or is cancelled): it cannot be dismissed. */
        NOT_DISMISSABLE,
        ALREADY_DISMISSED
    }

    /** The steps, in order. A test can fail the transaction after any of them. */
    public enum Step { LOCKED, ACCEPTANCE_REVERSED, STATUS_SET, SUGGESTIONS_REJECTED, DISMISSED, AUDIT }

    @FunctionalInterface
    public interface Hook {
        Hook NONE = (step, connection) -> {
        };

        void after(Step step, Connection connection) throws SQLException;
    }

    private final TenantScope scope;
    private final JdbcLedgerRepository ledger;
    private final MatchRepository matches;
    private final OrderRepository orders;
    private final Clock clock;
    private final Hook hook;

    @Autowired
    public RecordActionsService(DataSource dataSource, MatchRepository matches, OrderRepository orders, Clock clock) {
        this(dataSource, matches, orders, clock, Hook.NONE);
    }

    public RecordActionsService(DataSource dataSource, MatchRepository matches, OrderRepository orders, Clock clock, Hook hook) {
        this.scope = new TenantScope(dataSource);
        this.ledger = new JdbcLedgerRepository(scope);
        this.matches = matches;
        this.orders = orders;
        this.clock = clock;
        this.hook = hook;
    }

    public Outcome cancel(UUID tenantId, UUID orderId, UUID actor) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(actor, "actor");
        Outcome outcome = run(tenantId, c -> cancelWithin(c, tenantId, orderId, actor));
        LOG.info("order cancel tenant={} order={} outcome={}", tenantId, orderId, outcome);
        return outcome;
    }

    private Outcome cancelWithin(Connection c, UUID tenantId, UUID orderId, UUID actor) throws SQLException {
        if (!matches.isSellerWithin(c, tenantId, actor)) {
            return Outcome.NOT_A_SELLER;
        }
        Optional<MatchRepository.LockedOrder> locked = matches.lockOrder(c, tenantId, orderId);
        if (locked.isEmpty()) {
            return Outcome.NOT_FOUND;
        }
        hook.after(Step.LOCKED, c);
        switch (locked.get().status()) {
            case "PENDING_PAYMENT" -> { }
            case "PARTIALLY_PAID", "PAID" -> { return Outcome.HAS_PAYMENT; }
            case "CANCELLED" -> { return Outcome.ALREADY_CANCELLED; }
            default -> { return Outcome.NOT_PAYABLE; }
        }
        // (No separate check of the applied amount: a PENDING_PAYMENT order has none while the database is consistent (V016), and the
        // move to CANCELLED is refused with an active match (V021). A check here was deleted as redundant: register 173.)
        Instant now = clock.instant();
        Optional<UUID> acceptance = matches.liveAcceptanceWithin(c, tenantId, orderId);
        if (acceptance.isPresent()) {
            ledger.reverseWithin(c, tenantId, acceptance.get(), UUID.randomUUID(), now);
        }
        hook.after(Step.ACCEPTANCE_REVERSED, c);
        expectOne(matches.setOrderStatusWithin(c, tenantId, orderId, "CANCELLED"));
        hook.after(Step.STATUS_SET, c);
        matches.rejectOpenForOrderWithin(c, tenantId, orderId, actor, now);
        hook.after(Step.SUGGESTIONS_REJECTED, c);
        AuditLogRepository.appendWithin(c, tenantId, actor, "ORDER_CANCELLED", orderId.toString());
        hook.after(Step.AUDIT, c);
        return Outcome.DONE;
    }

    public Outcome dismissOrder(UUID tenantId, UUID orderId, UUID actor) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(actor, "actor");
        Outcome outcome = run(tenantId, c -> {
            if (!matches.isSellerWithin(c, tenantId, actor)) {
                return Outcome.NOT_A_SELLER;
            }
            Optional<MatchRepository.LockedOrder> locked = matches.lockOrder(c, tenantId, orderId);
            if (locked.isEmpty()) {
                return Outcome.NOT_FOUND;
            }
            hook.after(Step.LOCKED, c);
            if (orders.dismissedWithin(c, tenantId, orderId, null)) {
                return Outcome.ALREADY_DISMISSED;
            }
            String status = locked.get().status();
            if (!"UNPARSED".equals(status) && !"NEEDS_REVIEW".equals(status)) {
                return Outcome.NOT_DISMISSABLE;
            }
            return dismissed(c, tenantId, orderId, null, actor, "ORDER_DISMISSED", orderId);
        });
        LOG.info("order dismiss tenant={} order={} outcome={}", tenantId, orderId, outcome);
        return outcome;
    }

    public Outcome dismissPayment(UUID tenantId, UUID paymentId, UUID actor) {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(actor, "actor");
        Outcome outcome = run(tenantId, c -> {
            if (!matches.isSellerWithin(c, tenantId, actor)) {
                return Outcome.NOT_A_SELLER;
            }
            Optional<MatchRepository.LockedPayment> locked = matches.lockPayment(c, tenantId, paymentId);
            if (locked.isEmpty()) {
                return Outcome.NOT_FOUND;
            }
            hook.after(Step.LOCKED, c);
            if (orders.dismissedWithin(c, tenantId, null, paymentId)) {
                return Outcome.ALREADY_DISMISSED;
            }
            if (!"UNPARSED".equals(locked.get().status())) {
                return Outcome.NOT_DISMISSABLE;
            }
            return dismissed(c, tenantId, null, paymentId, actor, "PAYMENT_DISMISSED", paymentId);
        });
        LOG.info("payment dismiss tenant={} payment={} outcome={}", tenantId, paymentId, outcome);
        return outcome;
    }

    private Outcome dismissed(Connection c, UUID tenantId, UUID orderId, UUID paymentId, UUID actor, String action, UUID subject)
            throws SQLException {
        expectOne(orders.dismissWithin(c, tenantId, orderId, paymentId, actor, clock.instant()));
        hook.after(Step.DISMISSED, c);
        AuditLogRepository.appendWithin(c, tenantId, actor, action, subject.toString());
        hook.after(Step.AUDIT, c);
        return Outcome.DONE;
    }

    private Outcome run(UUID tenantId, TenantScope.SqlWork<Outcome> work) {
        try {
            return scope.call(tenantId, work);
        } catch (SQLException | LedgerException e) {
            throw new IllegalStateException("Could not change a record", e);
        }
    }

    private static void expectOne(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("expected one row, changed " + rows);
        }
    }
}
