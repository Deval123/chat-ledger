package dev.chatledger.suspicion.rules;

import dev.chatledger.matching.OrderFacts;
import dev.chatledger.matching.PaymentFacts;
import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.PairContext;
import dev.chatledger.suspicion.Rule;
import dev.chatledger.suspicion.SuspicionConfig;

import java.util.Optional;

/**
 * R7. Both sides have a phone and they differ: by the keyed hashes when both have one, otherwise by the last four digits when both have
 * those. A side with no phone at all proves nothing either way.
 */
public final class PhoneMismatchRule implements Rule<PairContext> {
    @Override
    public FlagCode code() {
        return FlagCode.PHONE_MISMATCH;
    }

    @Override
    public Optional<Finding> evaluate(PairContext pair, SuspicionConfig config) {
        OrderFacts o = pair.order();
        PaymentFacts p = pair.payment();
        boolean differ;
        if (o.customerPhoneHash() != null && p.senderPhoneHash() != null) {
            differ = !o.customerPhoneHash().equals(p.senderPhoneHash());
        } else if (o.customerLast4() != null && p.senderLast4() != null) {
            differ = !o.customerLast4().equals(p.senderLast4());
        } else {
            differ = false;
        }
        return differ ? Optional.of(Finding.pair(code(), p.id(), o.id())) : Optional.empty();
    }
}
