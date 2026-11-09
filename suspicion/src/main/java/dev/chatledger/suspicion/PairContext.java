package dev.chatledger.suspicion;

import dev.chatledger.matching.OrderFacts;
import dev.chatledger.matching.PaymentFacts;

import java.util.Objects;

/** One order and one payment, exactly as the scorer sees them (on the locked rows when the matcher is about to apply them). */
public record PairContext(OrderFacts order, PaymentFacts payment) {
    public PairContext {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(payment, "payment");
    }
}
