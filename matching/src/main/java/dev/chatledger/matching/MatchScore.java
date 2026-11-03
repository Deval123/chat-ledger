package dev.chatledger.matching;

import java.util.List;

/**
 * The numeric score and reasons produced by evaluating one order-payment pair.
 *
 * <p>Score range: 0–100.
 * <ul>
 *   <li>≥ 85: auto-match without asking the seller.</li>
 *   <li>60–84: suggest the match and wait for confirmation.</li>
 *   <li>&lt; 60: leave unmatched.</li>
 * </ul>
 *
 * <p>The list of reasons is shown in the UI so the seller understands why
 * the system made its decision (Requirement 3.4).
 *
 * @param score   Integer score 0–100.
 * @param reasons Ordered list of human-readable sentences explaining each contribution.
 */
public record MatchScore(int score, List<String> reasons) {

    /** Minimum score for an automatic match (no seller confirmation needed). */
    public static final int AUTO_MATCH_THRESHOLD   = 85;

    /** Minimum score for a suggested match (seller must confirm). */
    public static final int SUGGEST_THRESHOLD      = 60;

    public MatchScore {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("Score must be between 0 and 100, got: " + score);
        }
        reasons = List.copyOf(reasons);
    }

    public boolean isAutoMatch()  { return score >= AUTO_MATCH_THRESHOLD; }
    public boolean isSuggestion() { return score >= SUGGEST_THRESHOLD && score < AUTO_MATCH_THRESHOLD; }
    public boolean isUnmatched()  { return score < SUGGEST_THRESHOLD; }
}
