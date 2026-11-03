package dev.chatledger.suspicion;

import java.util.List;

/**
 * Strategy interface for a single suspicion rule.
 *
 * <p>Each concrete rule is a small, independently-testable class. Rules return
 * zero or more flags; they never throw on bad input — unexpected states produce
 * a flag rather than an exception.
 *
 * <p>Requirement: 5 (Suspicion flags).
 *
 * @param <C> The context object the rule needs (order, payment, match …).
 */
@FunctionalInterface
public interface SuspicionRule<C> {

    /**
     * Evaluates the rule against {@code context}.
     *
     * @param context Domain object to inspect; never null.
     * @return Zero or more advisory flags; never null.
     */
    List<SuspicionFlag> evaluate(C context);
}
