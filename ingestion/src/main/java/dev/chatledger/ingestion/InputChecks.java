package dev.chatledger.ingestion;

import dev.chatledger.extraction.OrderLimits;

import java.util.Optional;

/**
 * What the ingestion service demands of its input before doing anything with it. The HTTP edge already limits size and
 * encoding (task 3), but the service does not rely on its callers: a later entry point, a test or a bug must not be able
 * to hand it text it cannot store or parse safely. Input is refused, never repaired.
 *
 * <ul>
 *   <li>{@code externalId}: 1 to {@value #MAX_EXTERNAL_ID} characters from {@code A-Z a-z 0-9 . _ : -}.</li>
 *   <li>text: not blank, at most {@link OrderLimits#MAX_INPUT_CHARS} characters, no NUL, no control character other than
 *       tab and line breaks, no lone surrogate (PostgreSQL cannot store NUL, and a lone surrogate is not valid text).</li>
 * </ul>
 */
public final class InputChecks {

    public static final int MAX_EXTERNAL_ID = 128;

    private InputChecks() {
    }

    /** Checks an order message (up to {@link OrderLimits#MAX_INPUT_CHARS} characters). */
    public static Optional<RejectReason> check(String externalId, String text) {
        return check(externalId, text, OrderLimits.MAX_INPUT_CHARS);
    }

    /** Checks a message of at most {@code maxChars} characters (payment texts use {@code PaymentLimits.MAX_SMS_CHARS}). */
    public static Optional<RejectReason> check(String externalId, String text, int maxChars) {
        if (!validExternalId(externalId)) {
            return Optional.of(RejectReason.INVALID_EXTERNAL_ID);
        }
        if (text == null || text.isBlank()) {
            return Optional.of(RejectReason.BLANK_TEXT);
        }
        if (text.length() > maxChars) {
            return Optional.of(RejectReason.TEXT_TOO_LARGE);
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return Optional.of(RejectReason.INVALID_CHARACTERS);
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return Optional.of(RejectReason.INVALID_CHARACTERS);
            } else if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                return Optional.of(RejectReason.INVALID_CHARACTERS);
            }
        }
        return Optional.empty();
    }

    static boolean validExternalId(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_EXTERNAL_ID) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == ':' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
