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
 * R8. Both names are known (the neutral name of a customer known only by phone is not a name), they do not agree, and the phones are not
 * the same. When the phone hashes are equal it is the same person under a nickname, not a stranger.
 */
public final class NameMismatchRule implements Rule<PairContext> {
    @Override
    public FlagCode code() {
        return FlagCode.NAME_MISMATCH;
    }

    @Override
    public Optional<Finding> evaluate(PairContext pair, SuspicionConfig config) {
        OrderFacts o = pair.order();
        PaymentFacts p = pair.payment();
        if (o.customerPhoneHash() != null && o.customerPhoneHash().equals(p.senderPhoneHash())) {
            return Optional.empty();
        }
        if (!RuleNames.usable(o.customerName()) || !RuleNames.usable(p.senderName()) || RuleNames.agree(o.customerName(), p.senderName())) {
            return Optional.empty();
        }
        return Optional.of(Finding.pair(code(), p.id(), o.id()));
    }
}
