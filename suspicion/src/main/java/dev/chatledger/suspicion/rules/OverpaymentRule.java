package dev.chatledger.suspicion.rules;

import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.PairContext;
import dev.chatledger.suspicion.Rule;
import dev.chatledger.suspicion.SuspicionConfig;

import java.util.Optional;

/**
 * R11. The payment exceeds the balance by at least {@code overpay-min-bp} basis points of the balance: the overpay-and-refund trick. Integer
 * arithmetic only (amounts are at most 10^12, so the products stay below 10^16).
 */
public final class OverpaymentRule implements Rule<PairContext> {
    @Override
    public FlagCode code() {
        return FlagCode.OVERPAYMENT;
    }

    @Override
    public Optional<Finding> evaluate(PairContext pair, SuspicionConfig config) {
        long balance = pair.order().balanceMinor();
        long paid = pair.payment().amountMinor();
        // (paid <= balance needs no test of its own: then the excess is zero or negative, below any minimum of at least 1%.)
        if (balance <= 0 || (paid - balance) * 10_000 < balance * config.overpayMinBasisPoints()) {
            return Optional.empty();
        }
        return Optional.of(Finding.pair(code(), pair.payment().id(), pair.order().id()));
    }
}
