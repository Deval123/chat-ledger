package dev.chatledger.security;

/**
 * Too many password checks are running at once. Argon2 is deliberately memory-hungry, so the
 * number running at the same time is capped; the surplus is refused instead of queued forever.
 * Callers answer 429.
 */
public class LoginCapacityException extends RuntimeException {

    public LoginCapacityException() {
        super("Too many sign-in checks at once");
    }
}
