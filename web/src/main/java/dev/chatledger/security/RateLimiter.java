package dev.chatledger.security;

/**
 * Counts requests per key against a named rule. An interface so a shared implementation (database or
 * Redis) can replace the in-memory one when there is more than one node.
 */
public interface RateLimiter {

    /** Spends one token of {@code key} under the rule called {@code limiter}, if there is one. */
    RateDecision tryAcquire(String limiter, String key);

    /** Gives the key its full allowance back (used after a successful sign-in). */
    void reset(String limiter, String key);
}
