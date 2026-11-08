package dev.chatledger.matching;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Matching settings, {@code app.matching.*}. Bounds are checked by {@link MatchConfig}, so a value outside them stops the application
 * from starting instead of silently weakening the automatic match:
 *
 * <pre>
 * app.matching.auto-threshold=85      (75 to 100)
 * app.matching.suggest-threshold=60   (40 to 90; the automatic threshold must be at least 10 above it)
 * app.matching.auto-enabled=true      (kill switch: false means suggestions only)
 * app.matching.candidate-cap=200      (10 to 500)
 * app.matching.tie-margin=10          (5 to 30)
 * </pre>
 */
@ConfigurationProperties("app.matching")
public record MatchingProperties(
        @DefaultValue("85") int autoThreshold,
        @DefaultValue("60") int suggestThreshold,
        @DefaultValue("true") boolean autoEnabled,
        @DefaultValue("200") int candidateCap,
        @DefaultValue("10") int tieMargin) {

    /** The settings as a {@link MatchConfig}; building it checks every bound, and {@link MatchingConfig} does that at startup. */
    public MatchConfig toConfig() {
        return new MatchConfig(autoThreshold, suggestThreshold, autoEnabled, candidateCap, tieMargin);
    }
}
