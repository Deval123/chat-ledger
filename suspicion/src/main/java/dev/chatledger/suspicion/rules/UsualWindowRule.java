package dev.chatledger.suspicion.rules;

import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.PaymentContext;
import dev.chatledger.suspicion.Rule;
import dev.chatledger.suspicion.SuspicionConfig;

import java.util.Optional;

/**
 * R9. The payment time is more than {@code usual-age} before it was recorded, or more than {@code usual-ahead} after. This is a softer
 * window inside the hard one ({@code PaymentLimits}: 35 days back, 15 minutes ahead), outside which a payment is not readable at all.
 */
public final class UsualWindowRule implements Rule<PaymentContext> {
    @Override
    public FlagCode code() {
        return FlagCode.PAID_OUTSIDE_USUAL_WINDOW;
    }

    @Override
    public Optional<Finding> evaluate(PaymentContext p, SuspicionConfig config) {
        if (p.paidAt() == null) {
            return Optional.empty();
        }
        boolean old = p.paidAt().isBefore(p.recordedAt().minus(config.usualAge()));
        boolean ahead = p.paidAt().isAfter(p.recordedAt().plus(config.usualAhead()));
        return old || ahead ? Optional.of(Finding.payment(code(), p.paymentId())) : Optional.empty();
    }
}
