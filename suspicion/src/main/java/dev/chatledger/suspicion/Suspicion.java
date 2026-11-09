package dev.chatledger.suspicion;

import dev.chatledger.suspicion.rules.AmountNearMissRule;
import dev.chatledger.suspicion.rules.DuplicateTransactionRule;
import dev.chatledger.suspicion.rules.NameMismatchRule;
import dev.chatledger.suspicion.rules.NoProviderIdRule;
import dev.chatledger.suspicion.rules.OverpaymentRule;
import dev.chatledger.suspicion.rules.PaidBeforeOrderRule;
import dev.chatledger.suspicion.rules.PhoneMismatchRule;
import dev.chatledger.suspicion.rules.SenderAliasOrderRule;
import dev.chatledger.suspicion.rules.SenderAliasPaymentRule;
import dev.chatledger.suspicion.rules.SenderBurstRule;
import dev.chatledger.suspicion.rules.UsualWindowRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs the enabled rules of one kind on one context. Pure. The rules are fixed here (no discovery, no reflection), so the set is visible in
 * one place and a test can check that every code has a rule.
 */
public final class Suspicion {

    static final List<Rule<PaymentContext>> PAYMENT_RULES = List.of(new DuplicateTransactionRule(), new NoProviderIdRule(), new SenderBurstRule(),
            new UsualWindowRule(), new SenderAliasPaymentRule());
    static final List<Rule<OrderContext>> ORDER_RULES = List.of(new SenderAliasOrderRule());
    static final List<Rule<PairContext>> PAIR_RULES = List.of(new AmountNearMissRule(), new PaidBeforeOrderRule(), new PhoneMismatchRule(),
            new NameMismatchRule(), new OverpaymentRule());

    private final SuspicionConfig config;

    public Suspicion(SuspicionConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public SuspicionConfig config() {
        return config;
    }

    public List<Finding> payment(PaymentContext context) {
        return run(PAYMENT_RULES, context);
    }

    public List<Finding> order(OrderContext context) {
        return run(ORDER_RULES, context);
    }

    public List<Finding> pair(PairContext context) {
        return run(PAIR_RULES, context);
    }

    private <C> List<Finding> run(List<Rule<C>> rules, C context) {
        List<Finding> out = new ArrayList<>();
        for (Rule<C> rule : rules) {
            if (config.enabled(rule.code())) {
                rule.evaluate(context, config).ifPresent(out::add);
            }
        }
        return List.copyOf(out);
    }
}
