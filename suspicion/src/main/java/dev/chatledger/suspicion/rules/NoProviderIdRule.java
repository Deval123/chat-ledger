package dev.chatledger.suspicion.rules;

import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.PaymentContext;
import dev.chatledger.suspicion.Rule;
import dev.chatledger.suspicion.SuspicionConfig;

import java.util.Optional;

/** R2. No transaction id: a forward or a screenshot, stored as {@code UNVERIFIED}. */
public final class NoProviderIdRule implements Rule<PaymentContext> {
    @Override
    public FlagCode code() {
        return FlagCode.NO_PROVIDER_ID;
    }

    @Override
    public Optional<Finding> evaluate(PaymentContext p, SuspicionConfig config) {
        return "UNVERIFIED".equals(p.status()) ? Optional.of(Finding.payment(code(), p.paymentId())) : Optional.empty();
    }
}
