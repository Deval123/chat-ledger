package dev.chatledger.suspicion;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the payment rules may know about a payment that was just stored, in the transaction that stores it.
 *
 * @param status        the stored status ({@code RECEIVED}, {@code UNVERIFIED}, {@code DUPLICATE}; an {@code UNPARSED} payment is never evaluated)
 * @param paidAt        the time written in the message
 * @param recordedAt    the server's time when it was recorded
 * @param fromSender    earlier payments from the same sender phone, most recent first, at most {@value #MAX_SENDER_RECORDS};
 *                      empty when the payment has no sender phone
 */
public record PaymentContext(UUID paymentId, String status, Instant paidAt, Instant recordedAt, String senderPhoneHash, String senderName,
                             List<SenderRecord> fromSender) {
    /** The most earlier payments the repository reads for one sender (the bound of R6 and R10). */
    public static final int MAX_SENDER_RECORDS = 50;

    public PaymentContext {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(recordedAt, "recordedAt");
        fromSender = List.copyOf(fromSender);
        if (fromSender.size() > MAX_SENDER_RECORDS) {
            throw new IllegalArgumentException("too many sender records");
        }
    }

    @Override
    public String toString() {
        return "PaymentContext[" + paymentId + ", " + status + "]";
    }
}
