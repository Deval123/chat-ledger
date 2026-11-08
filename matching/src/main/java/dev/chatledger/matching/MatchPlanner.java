package dev.chatledger.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the scores of all candidates for one payment (or one order) into a decision. Pure.
 *
 * <ul>
 *   <li>{@link Kind#AUTO}: the best pair is eligible on its own (every floor, score at or above the automatic threshold) <b>and</b>
 *       beats every other candidate by at least the tie margin. This is the eighth floor.</li>
 *   <li>{@link Kind#SUGGEST}: otherwise, every candidate at or above the suggestion threshold, best first, at most
 *       {@value #MAX_SUGGESTIONS}. A best pair that was eligible but is tied is reported with {@code tied = true}.</li>
 *   <li>{@link Kind#NONE}: nothing reaches the suggestion threshold.</li>
 * </ul>
 * The result is a plan. Nothing is matched until {@code MatchingService.apply} re-checks it inside the database transaction.
 */
public final class MatchPlanner {

    public static final int MAX_SUGGESTIONS = 3;

    public enum Kind { AUTO, SUGGEST, NONE }

    /** @param auto the pair to match automatically (only for {@link Kind#AUTO}); @param suggestions the pairs to propose. */
    public record Plan(Kind kind, ScoredPair auto, List<ScoredPair> suggestions, boolean tied) {
        public Plan {
            suggestions = List.copyOf(suggestions);
        }
    }

    private static final Comparator<ScoredPair> BEST_FIRST = Comparator.comparingInt(ScoredPair::score).reversed()
            .thenComparing(p -> p.orderId().toString()).thenComparing(p -> p.paymentId().toString());

    private MatchPlanner() {
    }

    public static Plan plan(List<ScoredPair> candidates, MatchConfig config) {
        if (candidates.size() > config.candidateCap()) {
            throw new IllegalArgumentException("more candidates than the cap");
        }
        List<ScoredPair> ranked = new ArrayList<>(candidates);
        ranked.sort(BEST_FIRST);
        if (ranked.isEmpty()) {
            return new Plan(Kind.NONE, null, List.of(), false);
        }
        ScoredPair best = ranked.get(0);
        boolean tied = false;
        if (best.autoEligible()) {
            if (ranked.size() == 1 || best.score() - ranked.get(1).score() >= config.tieMargin()) {
                return new Plan(Kind.AUTO, best, List.of(), false);
            }
            tied = true;
        }
        List<ScoredPair> suggestions = new ArrayList<>();
        for (ScoredPair p : ranked) {
            if (p.score() >= config.suggestThreshold() && suggestions.size() < MAX_SUGGESTIONS) {
                suggestions.add(p);
            }
        }
        return new Plan(suggestions.isEmpty() ? Kind.NONE : Kind.SUGGEST, null, suggestions, tied);
    }
}
