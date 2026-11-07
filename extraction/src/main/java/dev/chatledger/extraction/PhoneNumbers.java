package dev.chatledger.extraction;

import java.util.Optional;

/**
 * The one definition of a phone number's canonical form. Everything that stores, hashes, compares or displays
 * a phone number goes through {@link #canonicalDigits(String)}: the order parser, the customer hash, the last four
 * digits and (tasks 5 and 6) the lookup of a payer from an SMS. A second spelling of this rule would make one
 * person two customers.
 *
 * <p>The rule: only digits, spaces, hyphens, dots and parentheses are allowed, and a {@code +} only as the first
 * character; those separators are dropped; a leading {@code +} or a leading {@code 00} (the international call
 * prefix) is removed; the result must be 7 to 15 digits (15 is the E.164 maximum). Anything else, such as a
 * letter, gives no phone number.
 *
 * <p><b>Limit, named:</b> there is no country logic. The same number written with a country code
 * ({@code +237 6 12 34 56 78}, {@code 00237612345678}) and without it ({@code 6 12 34 56 78}) gives different
 * canonical digits, so two customers. A national trunk prefix ({@code 0}) is not removed either.
 */
public final class PhoneNumbers {

    public static final int MIN_DIGITS = 7;
    public static final int MAX_DIGITS = 15;
    private static final int MAX_INPUT = 40;

    private PhoneNumbers() {
    }

    /** Canonical digits of {@code raw}, or empty if it is not a plausible phone number. */
    public static Optional<String> canonicalDigits(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.strip();
        if (s.isEmpty() || s.length() > MAX_INPUT) {
            return Optional.empty();
        }
        StringBuilder digits = new StringBuilder(s.length());
        boolean plus = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c == '+') {
                if (i != 0) {
                    return Optional.empty();
                }
                plus = true;
            } else if (c == ' ' || c == '-' || c == '.' || c == '(' || c == ')') {
                // separator, dropped
            } else {
                return Optional.empty();
            }
        }
        String d = digits.toString();
        if (!plus && d.startsWith("00")) {
            d = d.substring(2);
        }
        if (d.length() < MIN_DIGITS || d.length() > MAX_DIGITS) {
            return Optional.empty();
        }
        return Optional.of(d);
    }
}
