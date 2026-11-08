package dev.chatledger.orders;

import dev.chatledger.extraction.OrderLimits;
import dev.chatledger.ledger.LedgerException;
import dev.chatledger.ledger.jdbc.JdbcLedgerRepository;
import dev.chatledger.matching.ChartOfAccounts;
import dev.chatledger.matching.MatchRepository;
import dev.chatledger.matching.MatchTrigger;
import dev.chatledger.matching.Postings;
import dev.chatledger.security.AuditLogRepository;
import dev.chatledger.tenancy.TenantScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The seller states the total of an order that needs review, and it becomes payable. One database transaction:
 *
 * <ol>
 *   <li>ask the database whether the user is an enabled seller of the tenant;</li>
 *   <li>lock the order and require that it is still {@code NEEDS_REVIEW};</li>
 *   <li>require a total between 1 and the largest amount the system accepts;</li>
 *   <li>set the total and the status (the database allows the total to change only in this move);</li>
 *   <li>post {@code ORDER_ACCEPTED} (the same posting as at ingestion; one per order);</li>
 *   <li>append the audit row {@code ORDER_APPROVED} (who and when);</li>
 *   <li>commit: the database refuses the commit if the order became payable without its posting.</li>
 * </ol>
 * Any failure rolls everything back and the order stays {@code NEEDS_REVIEW}. After the commit the matcher is told about the order, best
 * effort, as after ingestion, so that a payment that was already waiting can match it.
 */
@Service
public class OrderApprovalService {

    public enum Outcome { APPROVED, NOT_A_SELLER, NOT_FOUND, NOT_REVIEWABLE, TOTAL_OUT_OF_RANGE }

    /** The steps, in order. A test can fail the transaction after any of them. */
    public enum Step { ORDER_LOCKED, TOTAL_SET, POSTED, AUDIT }

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
    private final MatchTrigger matcher;
    private final Clock clock;
    private final Hook hook;

    @Autowired
    public OrderApprovalService(DataSource dataSource, MatchRepository matches, OrderRepository orders, MatchTrigger matcher, Clock clock) {
        this(dataSource, matches, orders, matcher, clock, Hook.NONE);
    }

    public OrderApprovalService(DataSource dataSource, MatchRepository matches, OrderRepository orders, MatchTrigger matcher, Clock clock, Hook hook) {
        this.scope = new TenantScope(dataSource);
        this.ledger = new JdbcLedgerRepository(scope);
        this.matches = matches;
        this.orders = orders;
        this.matcher = matcher;
        this.clock = clock;
        this.hook = hook;
    }

    public Outcome approve(UUID tenantId, UUID orderId, UUID actor, long totalMinor) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(actor, "actor");
        Outcome outcome;
        try {
            outcome = scope.call(tenantId, c -> approveWithin(c, tenantId, orderId, actor, totalMinor));
        } catch (SQLException | LedgerException e) {
            throw new IllegalStateException("Could not approve an order", e);
        }
        if (outcome == Outcome.APPROVED) {
            try {
                matcher.orderStored(tenantId, orderId);
            } catch (RuntimeException ignored) {
                // the matcher logs its own failures; the order is payable and posted either way
            }
        }
        return outcome;
    }

    private Outcome approveWithin(Connection c, UUID tenantId, UUID orderId, UUID actor, long totalMinor) throws SQLException {
        if (!matches.isSellerWithin(c, tenantId, actor)) {
            return Outcome.NOT_A_SELLER;
        }
        Optional<MatchRepository.LockedOrder> locked = matches.lockOrder(c, tenantId, orderId);
        if (locked.isEmpty()) {
            return Outcome.NOT_FOUND;
        }
        hook.after(Step.ORDER_LOCKED, c);
        if (!"NEEDS_REVIEW".equals(locked.get().status())) {
            return Outcome.NOT_REVIEWABLE;
        }
        if (totalMinor < 1 || totalMinor > OrderLimits.MAX_MINOR) {
            return Outcome.TOTAL_OUT_OF_RANGE;
        }
        if (orders.approveWithin(c, tenantId, orderId, totalMinor) != 1) {
            throw new IllegalStateException("the approval changed no row of a locked order");
        }
        hook.after(Step.TOTAL_SET, c);
        ChartOfAccounts.Accounts accounts = ChartOfAccounts.ensure(c, tenantId);
        ledger.appendWithin(c, Postings.orderAccepted(tenantId, orderId, totalMinor, accounts, clock.instant()));
        hook.after(Step.POSTED, c);
        AuditLogRepository.appendWithin(c, tenantId, actor, "ORDER_APPROVED", orderId.toString());
        hook.after(Step.AUDIT, c);
        return Outcome.APPROVED;
    }
}
