package dev.chatledger.matching;

/**
 * The settings of the matcher, with hard bounds. A value outside its bounds is refused when the object is built, so the
 * application does not start with a threshold that would weaken the defense (see {@code MatchingProperties}).
 *
 * <p>Only these values are configuration. The weights, the amount tiers, the time window and the floors for an automatic match
 * are constants of {@link MatchScorer}: lowering a threshold to its lowest allowed value still cannot produce an automatic match
 * without a reference, a transaction id and an exact amount.
 *
 * @param autoThreshold    Lowest score for an automatic match (75 to 100).
 * @param suggestThreshold Lowest score for a suggestion (at least 40, and at least {@value #MIN_GAP} below the automatic threshold, so at most 90).
 * @param autoEnabled      Kill switch: when false the matcher only suggests.
 * @param candidateCap     Most candidates scored per run (10 to 500).
 * @param tieMargin        An automatic match needs the best score to beat every other candidate by at least this much (5 to 30).
 */
public record MatchConfig(int autoThreshold, int suggestThreshold, boolean autoEnabled, int candidateCap, int tieMargin) {

    public static final int DEFAULT_AUTO = 85;
    public static final int DEFAULT_SUGGEST = 60;
    public static final int DEFAULT_CANDIDATE_CAP = 200;
    public static final int DEFAULT_TIE_MARGIN = 10;
    /** The automatic threshold must exceed the suggestion threshold by at least this much. */
    public static final int MIN_GAP = 10;

    public MatchConfig {
        require("app.matching.auto-threshold", autoThreshold, 75, 100);
        if (suggestThreshold < 40) {
            throw new IllegalArgumentException("app.matching.suggest-threshold must be at least 40");
        }
        require("app.matching.candidate-cap", candidateCap, 10, 500);
        require("app.matching.tie-margin", tieMargin, 5, 30);
        if (autoThreshold < suggestThreshold + MIN_GAP) {
            throw new IllegalArgumentException("app.matching.auto-threshold must be at least app.matching.suggest-threshold + " + MIN_GAP);
        }
    }

    public static MatchConfig defaults() {
        return new MatchConfig(DEFAULT_AUTO, DEFAULT_SUGGEST, true, DEFAULT_CANDIDATE_CAP, DEFAULT_TIE_MARGIN);
    }

    private static void require(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
    }
}
