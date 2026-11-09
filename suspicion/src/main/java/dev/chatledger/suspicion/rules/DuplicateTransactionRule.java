package dev.chatledger.suspicion.rules;

import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.PaymentContext;
import dev.chatledger.suspicion.Rule;
import dev.chatledger.suspicion.SuspicionConfig;

import java.util.Optional;

/** R1. The transaction id was already recorded for this workspace and provider, so ingestion stored the payment as {@code DUPLICATE}. */
public final class DuplicateTransactionRule implements Rule<PaymentContext> {
    @Override
    public FlagCode code() {
        return FlagCode.DUPLICATE_TX;
    }

    @Override
    public Optional<Finding> evaluate(PaymentContext p, SuspicionConfig config) {
        return "DUPLICATE".equals(p.status()) ? Optional.of(Finding.payment(code(), p.paymentId())) : Optional.empty();
    }
}
