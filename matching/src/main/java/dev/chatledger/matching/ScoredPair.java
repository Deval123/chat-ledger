package dev.chatledger.matching;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The result of scoring one order and one payment: a score from 0 to 100, the reasons (codes), the floors that hold, and whether the
 * pair qualifies for an automatic match on its own (every floor and a score at or above the threshold). The planner still has to check
 * that no other candidate is close.
 */
public record ScoredPair(UUID orderId, UUID paymentId, int score, List<Reason> reasons, Set<FloorCode> floorsMet, boolean autoEligible) {

    public ScoredPair {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("Score must be between 0 and 100, got: " + score);
        }
        reasons = List.copyOf(reasons);
        floorsMet = Set.copyOf(floorsMet);
    }

    public String reasonsJson() {
        return Reason.toJson(reasons);
    }
}
