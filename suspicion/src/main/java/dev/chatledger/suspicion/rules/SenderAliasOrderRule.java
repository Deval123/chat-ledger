package dev.chatledger.suspicion.rules;

import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.OrderContext;
import dev.chatledger.suspicion.Rule;
import dev.chatledger.suspicion.SuspicionConfig;

import java.util.Optional;

/**
 * R10, order side. The order's phone already belonged to a customer whose name does not agree with the name in this order. (The stored
 * customer keeps its first name, so without this flag the new name would be visible only in the raw message.)
 */
public final class SenderAliasOrderRule implements Rule<OrderContext> {
    @Override
    public FlagCode code() {
        return FlagCode.SENDER_ALIAS;
    }

    @Override
    public Optional<Finding> evaluate(OrderContext o, SuspicionConfig config) {
        if (!RuleNames.usable(o.parsedName()) || !RuleNames.usable(o.storedName())
                || RuleNames.agree(o.parsedName(), o.storedName())) {
            return Optional.empty();
        }
        return Optional.of(Finding.order(code(), o.orderId()));
    }
}
