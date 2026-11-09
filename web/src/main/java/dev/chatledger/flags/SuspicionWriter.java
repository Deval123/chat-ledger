package dev.chatledger.flags;

import dev.chatledger.matching.OrderFacts;
import dev.chatledger.matching.PaymentFacts;
import dev.chatledger.orders.OrderRepository;
import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.OrderContext;
import dev.chatledger.suspicion.PairContext;
import dev.chatledger.suspicion.PaymentContext;
import dev.chatledger.suspicion.SenderRecord;
import dev.chatledger.suspicion.Suspicion;
import dev.chatledger.suspicion.SuspicionConfig;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Runs the rules and writes what they find, always on the caller's connection: a payment and its flags, an order and its flag, the matcher's
 * decision and the flags of the pair commit together or not at all (task 7, D5). There is no method that opens a transaction of its own.
 */
@Component
public class SuspicionWriter {

    private final Suspicion suspicion;
    private final FlagRepository flags;
    private final Clock clock;

    public SuspicionWriter(Suspicion suspicion, FlagRepository flags, Clock clock) {
        this.suspicion = Objects.requireNonNull(suspicion, "suspicion");
        this.flags = Objects.requireNonNull(flags, "flags");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The approved rules and defaults, for code built outside Spring (tests and fixtures). */
    public static SuspicionWriter withDefaults(Clock clock) {
        return new SuspicionWriter(new Suspicion(SuspicionConfig.defaults()), new FlagRepository(), clock);
    }

    /** The payment rules for a payment just inserted on {@code c}. (An {@code UNPARSED} payment has no time and no phone, so no rule fires.) */
    public List<Finding> paymentWithin(Connection c, UUID tenantId, UUID paymentId, String status, Instant paidAt, String senderPhoneHash,
                                       String senderName) throws SQLException {
        List<SenderRecord> fromSender = senderPhoneHash == null || paidAt == null ? List.of()
                : flags.senderRecordsWithin(c, tenantId, senderPhoneHash, paymentId, paidAt);
        List<Finding> found = suspicion.payment(new PaymentContext(paymentId, status, paidAt, clock.instant(), senderPhoneHash, senderName, fromSender));
        flags.insertAllWithin(c, tenantId, found, clock.instant());
        return found;
    }

    /** The order rule for an order just inserted on {@code c}. */
    public List<Finding> orderWithin(Connection c, UUID tenantId, UUID orderId, String parsedName, OrderRepository.CustomerResult customer)
            throws SQLException {
        List<Finding> found = suspicion.order(new OrderContext(orderId, parsedName, customer == null ? null : customer.storedName()));
        flags.insertAllWithin(c, tenantId, found, clock.instant());
        return found;
    }

    /** The pair rules for one order and one payment; the findings are written on {@code c}. */
    public List<Finding> pairWithin(Connection c, UUID tenantId, OrderFacts order, PaymentFacts payment) throws SQLException {
        List<Finding> found = suspicion.pair(new PairContext(order, payment));
        flags.insertAllWithin(c, tenantId, found, clock.instant());
        return found;
    }

    /** The flag state of a pair, read on {@code c} (see {@link FlagRepository#stateWithin}). */
    public FlagRepository.FlagState stateWithin(Connection c, UUID tenantId, UUID paymentId, UUID orderId) throws SQLException {
        return flags.stateWithin(c, tenantId, paymentId, orderId);
    }
}
