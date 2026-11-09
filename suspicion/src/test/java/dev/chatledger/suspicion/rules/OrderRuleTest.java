package dev.chatledger.suspicion.rules;

import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.suspicion.OrderContext;
import dev.chatledger.suspicion.SubjectType;
import dev.chatledger.suspicion.SuspicionConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static dev.chatledger.suspicion.rules.RuleTables.assertFlag;
import static org.assertj.core.api.Assertions.assertThat;

/** R10 on an order: the order's phone already belonged to a customer under another name. Requirement 5.6. Task 7. */
class OrderRuleTest {
    private static final SuspicionConfig C = SuspicionConfig.defaults();
    private final SenderAliasOrderRule r = new SenderAliasOrderRule();

    private Optional<Finding> eval(String parsed, String stored) {
        return r.evaluate(new OrderContext(UUID.randomUUID(), parsed, stored), C);
    }

    @Test
    void an_existing_phone_under_another_name_is_flagged_on_the_order() {
        Optional<Finding> f = eval("Bob Synthetic", "Ana Kamga");
        assertFlag(f, true, FlagCode.SENDER_ALIAS, "another name");
        assertThat(f.get().subject()).isEqualTo(SubjectType.ORDER);
        assertThat(f.get().paymentId()).isNull();
        assertFlag(eval("A. Kamga", "Ana Kamga"), false, FlagCode.SENDER_ALIAS, "an initial agrees");
        assertFlag(eval("Ana Kamga", "Ana Kamga"), false, FlagCode.SENDER_ALIAS, "the same name");
        assertFlag(eval(null, "Ana Kamga"), false, FlagCode.SENDER_ALIAS, "no name in the order");
        assertFlag(eval("  ", "Ana Kamga"), false, FlagCode.SENDER_ALIAS, "a blank name");
        assertFlag(eval("Bob Synthetic", "Bob Synthetic"), false, FlagCode.SENDER_ALIAS, "a new phone: the customer was stored with this name");
        assertFlag(eval("Bob Synthetic", null), false, FlagCode.SENDER_ALIAS, "no phone, so no customer by phone");
        assertFlag(eval("Bob Synthetic", "Unknown customer"), false, FlagCode.SENDER_ALIAS, "the customer had only the neutral name");
        assertFlag(eval("Al", "al"), false, FlagCode.SENDER_ALIAS, "the same short name");
    }
}
