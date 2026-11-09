package dev.chatledger.suspicion.rules;

import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.PaymentContext;
import dev.chatledger.suspicion.Rule;
import dev.chatledger.suspicion.SenderRecord;
import dev.chatledger.suspicion.SuspicionConfig;

import java.time.Instant;
import java.util.Optional;

/** R10, payment side. The same sender phone gave a name that does not agree with this one, on a payment paid within {@code alias-lookback} before it. */
public final class SenderAliasPaymentRule implements Rule<PaymentContext> {
    @Override
    public FlagCode code() {
        return FlagCode.SENDER_ALIAS;
    }

    @Override
    public Optional<Finding> evaluate(PaymentContext p, SuspicionConfig config) {
        if (p.senderPhoneHash() == null || p.paidAt() == null || !RuleNames.usable(p.senderName())) {
            return Optional.empty();
        }
        Instant from = p.paidAt().minus(config.aliasLookback());
        for (SenderRecord earlier : p.fromSender()) {
            if (!earlier.paidAt().isBefore(from) && RuleNames.usable(earlier.name()) && !RuleNames.agree(earlier.name(), p.senderName())) {
                return Optional.of(Finding.payment(code(), p.paymentId()));
            }
        }
        return Optional.empty();
    }
}
