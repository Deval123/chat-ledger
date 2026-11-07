package dev.chatledger.security;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalisation and validation of the two identifiers typed at login and at provisioning:
 * the workspace code and the email. Anything that does not fit is rejected, not repaired.
 * The database enforces the same shapes (V010), so a value that passes here is accepted there.
 */
public final class Identifiers {

    private static final Pattern WORKSPACE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@\\p{Cntrl}]{1,64}@[^\\s@\\p{Cntrl}]{1,189}$");

    /** A value no real workspace code can equal; used to keep the failure path doing the same work. */
    public static final String IMPOSSIBLE_WORKSPACE = "-";

    private Identifiers() {
    }

    /** Lower-case and trim, then check the shape. Empty if it is not a valid workspace code. */
    public static Optional<String> workspace(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        return WORKSPACE.matcher(value).matches() ? Optional.of(value) : Optional.empty();
    }

    /** Lower-case and trim, then check the shape. Empty if it is not a plausible email address. */
    public static Optional<String> email(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.length() < 3 || value.length() > 254 || !EMAIL.matcher(value).matches()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
