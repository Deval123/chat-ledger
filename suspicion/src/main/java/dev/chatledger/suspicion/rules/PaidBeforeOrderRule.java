package dev.chatledger.suspicion.rules;

import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.PairContext;
import dev.chatledger.suspicion.Rule;
import dev.chatledger.suspicion.SuspicionConfig;

import java.time.Instant;
import java.util.Optional;

/** R5. The payment time is earlier than the order's creation by more than {@code before-order-tolerance} (paying first and typing the order a moment later is normal). */
public final class PaidBeforeOrderRule implements Rule<PairContext> {
    @Override
    public FlagCode code() {
        return FlagCode.PAID_BEFORE_ORDER;
    }

    @Override
    public Optional<Finding> evaluate(PairContext pair, SuspicionConfig config) {
        Instant paidAt = pair.payment().paidAt();
        if (paidAt == null || !paidAt.isBefore(pair.order().createdAt().minus(config.beforeOrderTolerance()))) {
            return Optional.empty();
        }
        return Optional.of(Finding.pair(code(), pair.payment().id(), pair.order().id()));
    }
}
