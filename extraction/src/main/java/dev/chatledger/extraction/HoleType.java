package dev.chatledger.extraction;

import java.util.Optional;

/**
 * The typed holes of a payment template. Each hole has its own character set and its own minimum and maximum length; the
 * template scanner consumes only those characters, at most that many. A captured value is still parsed and checked by a typed
 * parser afterwards; the character set is the first, coarse gate.
 *
 * <p>The sets are deliberately narrower than the database CHECKs: they stop at characters a template is likely to use as a
 * delimiter. For example a transaction id or reference hole reads letters, digits, {@code _} and {@code -}, so a {@code .}
 * can follow it as a literal.
 */
public enum HoleType {

    AMOUNT("amount", 1, 20),
    CURRENCY("currency", 3, 6),
    SENDER_NAME("senderName", PaymentLimits.MIN_NAME, PaymentLimits.MAX_NAME),
    SENDER_PHONE("senderPhone", 7, 20),
    SENDER_LAST4("senderLast4", 4, 4),
    TRANSACTION_ID("transactionId", PaymentLimits.MIN_TRANSACTION_ID, PaymentLimits.MAX_TRANSACTION_ID),
    PAID_AT("paidAt", 10, 25),
    REFERENCE("reference", 1, 64);

    private final String fieldName;
    private final int min;
    private final int max;

    HoleType(String fieldName, int min, int max) {
        this.fieldName = fieldName;
        this.min = min;
        this.max = max;
    }

    public String fieldName() {
        return fieldName;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public static Optional<HoleType> byName(String name) {
        for (HoleType t : values()) {
            if (t.fieldName.equals(name)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** True if {@code c} may be part of this hole's value. */
    public boolean allows(char c) {
        boolean digit = c >= '0' && c <= '9';
        boolean asciiLetter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        return switch (this) {
            case AMOUNT -> digit || c == ' ' || c == ',' || c == '.' || c == '\'';
            case CURRENCY -> asciiLetter;
            case SENDER_NAME -> Character.isLetter(c) || c == ' ' || c == '.' || c == '\'' || c == '-';
            case SENDER_PHONE -> digit || c == '+' || c == ' ' || c == '-' || c == '.';
            case SENDER_LAST4 -> digit;
            case TRANSACTION_ID, REFERENCE -> digit || asciiLetter || c == '_' || c == '-';
            case PAID_AT -> digit || c == '/' || c == '-' || c == ':' || c == '.' || c == ' ';
        };
    }
}
