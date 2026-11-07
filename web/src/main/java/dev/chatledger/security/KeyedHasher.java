package dev.chatledger.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Keyed, one-way short identifiers for log lines and audit rows, so a log can show that two events
 * involve the same submitted account without ever containing the email or the workspace code.
 *
 * <p>The key is {@code app.secret}. The application refuses to start if it is missing, short, or still
 * a placeholder, so a forgotten secret cannot silently produce guessable identifiers.
 */
@Component
public class KeyedHasher {

    static final int MIN_SECRET_LENGTH = 32;

    private final byte[] key;

    public KeyedHasher(@Value("${app.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.secret (APP_SECRET) is not set");
        }
        if (secret.startsWith("<") || secret.toLowerCase().contains("replace")) {
            throw new IllegalStateException("app.secret (APP_SECRET) is still a placeholder");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("app.secret (APP_SECRET) must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Twelve hex characters of HMAC-SHA-256 over the input. Stable, not reversible. */
    public String shortHash(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 is not available", e);
        }
    }
}
