package dev.chatledger.suspicion;

import java.util.Optional;

/**
 * One suspicion rule. Pure: it reads its context and the configuration and nothing else (no clock, no database, no regular expression; a
 * source scan checks it). At most one finding per evaluation, so a rule can never add more than one flag per subject.
 */
public interface Rule<C> {
    FlagCode code();

    Optional<Finding> evaluate(C context, SuspicionConfig config);
}
