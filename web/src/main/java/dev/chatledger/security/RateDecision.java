package dev.chatledger.security;

/** The answer of a rate limiter: allowed, or how many seconds until a request would be. */
public record RateDecision(boolean allowed, long retryAfterSeconds) {

    public static RateDecision permit() {
        return new RateDecision(true, 0);
    }

    public static RateDecision deny(long retryAfterSeconds) {
        return new RateDecision(false, Math.max(1, retryAfterSeconds));
    }
}
