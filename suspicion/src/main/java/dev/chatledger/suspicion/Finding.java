package dev.chatledger.suspicion;

import java.util.Objects;
import java.util.UUID;

/**
 * What a rule found: a code and a subject, nothing else. No time, no id of its own, no text from a message: the repository adds those.
 *
 * <ul>
 *   <li>{@link SubjectType#PAYMENT} with no order: about the payment, so it concerns every pair of that payment;</li>
 *   <li>{@link SubjectType#PAYMENT} with an order: about that pair only;</li>
 *   <li>{@link SubjectType#ORDER}: about the order, so it concerns every pair of that order. It never names a payment.</li>
 * </ul>
 */
public record Finding(FlagCode code, SubjectType subject, UUID paymentId, UUID orderId) {
    public Finding {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(subject, "subject");
        if (subject == SubjectType.PAYMENT && paymentId == null) {
            throw new IllegalArgumentException("a payment finding names its payment");
        }
        if (subject == SubjectType.ORDER && (orderId == null || paymentId != null)) {
            throw new IllegalArgumentException("an order finding names its order and no payment");
        }
    }

    public static Finding payment(FlagCode code, UUID paymentId) {
        return new Finding(code, SubjectType.PAYMENT, paymentId, null);
    }

    public static Finding pair(FlagCode code, UUID paymentId, UUID orderId) {
        return new Finding(code, SubjectType.PAYMENT, paymentId, Objects.requireNonNull(orderId, "orderId"));
    }

    public static Finding order(FlagCode code, UUID orderId) {
        return new Finding(code, SubjectType.ORDER, null, orderId);
    }

    /** True for a payment finding that names an order. */
    public boolean isPair() {
        return subject == SubjectType.PAYMENT && orderId != null;
    }
}
