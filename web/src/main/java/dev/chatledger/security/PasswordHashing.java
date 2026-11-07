package dev.chatledger.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * The one place password hashing is configured.
 *
 * <p>Argon2id with explicit parameters at OWASP's minimum for it (19 MiB, 2 passes, 1 lane), not
 * the framework's defaults. The stored value is {@code {argon2id}$argon2id$v=19$m=19456,t=2,p=1$salt$hash},
 * so the algorithm and cost travel with the hash and can be raised later. A database check
 * (V013) refuses anything that does not have that shape, so plaintext cannot be stored.
 *
 * <p>Used by the web application and by the operator provisioning commands, so both produce
 * identical hashes.
 */
public final class PasswordHashing {

    public static final String ID = "argon2id";
    public static final int MEMORY_KIB = 19_456;
    public static final int ITERATIONS = 2;
    public static final int PARALLELISM = 1;
    public static final int SALT_BYTES = 16;
    public static final int HASH_BYTES = 32;

    private PasswordHashing() {
    }

    public static PasswordEncoder create() {
        var argon2 = new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
        return new DelegatingPasswordEncoder(ID, Map.of(ID, argon2));
    }
}
