package dev.chatledger.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a new password must satisfy. Length is the rule that matters (12 to 128 characters), with no
 * composition rules; the rest keep out the obvious choices.
 *
 * <p>There is deliberately no call to an online breached-password service: it would send data
 * off the server. The bundled list is small and only catches the most common passwords, so
 * length and Argon2id are the real defenses.
 *
 * <p>The result names the problems, never the password.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    public enum Problem {
        TOO_SHORT("The password must be at least " + MIN_LENGTH + " characters long."),
        TOO_LONG("The password must be at most " + MAX_LENGTH + " characters long."),
        CONTROL_CHARACTERS("The password must not contain control characters."),
        CONTAINS_EMAIL_NAME("The password must not contain the part of the email before the @."),
        CONTAINS_WORKSPACE("The password must not contain the workspace code."),
        TOO_COMMON("The password is too common."),
        LOW_VARIETY("The password uses too few different characters.");

        private final String message;

        Problem(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private static final Set<String> COMMON = loadCommon();

    private PasswordPolicy() {
    }

    /**
     * @param email     the user's email, used to reject passwords built from it (may be null)
     * @param workspace the workspace code, used the same way (may be null)
     * @return the problems found; empty means the password is acceptable
     */
    public static List<Problem> check(String password, String email, String workspace) {
        List<Problem> problems = new ArrayList<>();
        if (password == null) {
            problems.add(Problem.TOO_SHORT);
            return problems;
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH) {
            problems.add(Problem.TOO_SHORT);
        }
        if (length > MAX_LENGTH) {
            problems.add(Problem.TOO_LONG);
        }
        if (password.codePoints().anyMatch(Character::isISOControl)) {
            problems.add(Problem.CONTROL_CHARACTERS);
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (email != null) {
            int at = email.indexOf('@');
            String local = (at > 0 ? email.substring(0, at) : email).toLowerCase(Locale.ROOT);
            if (local.length() >= 4 && lower.contains(local)) {
                problems.add(Problem.CONTAINS_EMAIL_NAME);
            }
        }
        if (workspace != null && workspace.length() >= 3 && lower.contains(workspace.toLowerCase(Locale.ROOT))) {
            problems.add(Problem.CONTAINS_WORKSPACE);
        }
        if (COMMON.contains(lower)) {
            problems.add(Problem.TOO_COMMON);
        }
        if (password.codePoints().distinct().count() < 5) {
            problems.add(Problem.LOW_VARIETY);
        }
        return problems;
    }

    private static Set<String> loadCommon() {
        try (InputStream in = PasswordPolicy.class.getResourceAsStream("/common-passwords.txt")) {
            if (in == null) {
                throw new IllegalStateException("common-passwords.txt is missing from the classpath");
            }
            Set<String> set = new HashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                reader.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                        .forEach(l -> set.add(l.toLowerCase(Locale.ROOT)));
            }
            return Set.copyOf(set);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
