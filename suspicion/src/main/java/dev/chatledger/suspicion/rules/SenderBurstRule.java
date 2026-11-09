package dev.chatledger.suspicion.rules;

import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.PaymentContext;
import dev.chatledger.suspicion.Rule;
import dev.chatledger.suspicion.SenderRecord;
import dev.chatledger.suspicion.SuspicionConfig;

import java.time.Instant;
import java.util.Optional;

/**
 * R6. At least {@code burst-count} payments from the same sender phone were paid within {@code burst-window} up to this one, this one
 * included. A payment without a sender phone (a forward with only the last four digits) is not evaluated: it is already R2.
 */
public final class SenderBurstRule implements Rule<PaymentContext> {
    @Override
    public FlagCode code() {
        return FlagCode.SENDER_BURST;
    }

    @Override
    public Optional<Finding> evaluate(PaymentContext p, SuspicionConfig config) {
        if (p.senderPhoneHash() == null || p.paidAt() == null) {
            return Optional.empty();
        }
        Instant from = p.paidAt().minus(config.burstWindow());
        int count = 1;
        for (SenderRecord earlier : p.fromSender()) {
            if (!earlier.paidAt().isBefore(from) && !earlier.paidAt().isAfter(p.paidAt())) {
                count++;
            }
        }
        return count >= config.burstCount() ? Optional.of(Finding.payment(code(), p.paymentId())) : Optional.empty();
    }
}
