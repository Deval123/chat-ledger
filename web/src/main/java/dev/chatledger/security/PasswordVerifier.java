package dev.chatledger.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Checks a password against a stored hash, with at most a few checks running at once.
 *
 * <p>Each Argon2 check uses about 19 MiB. Without a cap, a flood of login requests could
 * exhaust memory. With it, extra requests wait briefly and are then refused
 * ({@link LoginCapacityException}). Unknown accounts are checked against a dummy hash made at
 * startup, through the same gate, so they cost the same and take the same time as real ones.
 */
@Component
public class PasswordVerifier {

    private final PasswordEncoder encoder;
    private final Semaphore gate;
    private final Duration wait;
    private final String dummyHash;

    @Autowired
    public PasswordVerifier(PasswordEncoder encoder, SecurityProperties properties) {
        this(encoder, properties.hashing().maxConcurrent(), properties.hashing().waitTime());
    }

    PasswordVerifier(PasswordEncoder encoder, int permits, Duration wait) {
        this.encoder = encoder;
        this.gate = new Semaphore(permits, true);
        this.wait = wait;
        // A real hash of a password nobody knows, made once. Never stored anywhere.
        this.dummyHash = encoder.encode(UUID.randomUUID() + UUID.randomUUID().toString());
    }

    /** True if the password matches. A hash this code cannot read counts as no match. */
    public boolean matches(String rawPassword, String storedHash) {
        acquire();
        try {
            return encoder.matches(rawPassword, storedHash);
        } catch (IllegalArgumentException | IllegalStateException unreadableHash) {
            // Unknown scheme (IllegalArgument) or damaged Argon2 encoding (IllegalState): no match, never a 500.
            return false;
        } finally {
            gate.release();
        }
    }

    /** The hash to check against when the account does not exist. */
    public String dummyHash() {
        return dummyHash;
    }

    int availablePermits() {
        return gate.availablePermits();
    }

    private void acquire() {
        try {
            if (!gate.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new LoginCapacityException();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LoginCapacityException();
        }
    }
}
