package dev.chatledger.board;

import org.springframework.stereotype.Component;

/**
 * Phone numbers on a page. The structured fields hold only the last four digits ({@link #display}). A pasted message can still hold a
 * whole number, so {@link #maskText} finds numbers by counting digits, not by looking for one unbroken run: a number written
 * {@code 6 99 00 12 34}, {@code 699.001.234}, {@code (+237) 699-001-234} or {@code +999 000 000 1234} has the same digits as one written without
 * separators.
 *
 * <p>Rule: digits (any script) joined by separators (space, no-break space, dot, dash, parenthesis) make one number as long as no more than three
 * separator characters follow each other (") " and " - " occur in real numbers). A number of seven or more digits keeps its last four and every other digit becomes a bullet. This is
 * a heuristic: it also masks other long digit groups (a date written {@code 2026-11-03}), and misses a number broken by letters or written in words.
 *
 * <p>Registered as a bean named {@code phone} for templates.
 */
@Component("phone")
public final class PhoneMask {

    static final int MIN_DIGITS = 7;
    static final int KEEP = 4;
    /** Separator characters allowed in a row inside one number (") " is two, " - " is three). */
    static final int MAX_SEPARATORS = 3;
    static final char BULLET = '\u2022';

    /** "•••• 1234" for a stored last-four, or an empty string if it is not four digits. */
    public String display(String last4) {
        if (last4 == null || last4.length() != 4) {
            return "";
        }
        for (int i = 0; i < 4; i++) {
            if (last4.charAt(i) < '0' || last4.charAt(i) > '9') {
                return "";
            }
        }
        return "\u2022\u2022\u2022\u2022 " + last4;
    }

    /** The spoken form: "phone ending 1234", or an empty string. */
    public String spoken(String last4) {
        return display(last4).isEmpty() ? "" : "phone ending " + last4;
    }

    /** The text with every number of {@value #MIN_DIGITS} or more digits masked except its last {@value #KEEP} digits. */
    public String maskText(String text) {
        return mask(text);
    }

    static String mask(String text) {
        if (text == null) {
            return "";
        }
        int n = text.length();
        StringBuilder out = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            int cp = text.codePointAt(i);
            if (!Character.isDigit(cp)) {
                out.appendCodePoint(cp);
                i += Character.charCount(cp);
                continue;
            }
            int j = i;
            int lastDigitEnd = i;
            int digits = 0;
            int run = 0;
            while (j < n) {
                int c = text.codePointAt(j);
                if (Character.isDigit(c)) {
                    digits++;
                    run = 0;
                    j += Character.charCount(c);
                    lastDigitEnd = j;
                } else if (isSeparator(c)) {
                    run++;
                    if (run > MAX_SEPARATORS) {
                        break;
                    }
                    j += Character.charCount(c);
                } else {
                    break;
                }
            }
            String segment = text.substring(i, lastDigitEnd);
            if (digits < MIN_DIGITS) {
                out.append(segment);
            } else {
                int seen = 0;
                for (int k = 0; k < segment.length(); ) {
                    int c = segment.codePointAt(k);
                    k += Character.charCount(c);
                    if (Character.isDigit(c)) {
                        seen++;
                        out.appendCodePoint(seen > digits - KEEP ? c : BULLET);
                    } else {
                        out.appendCodePoint(c);
                    }
                }
            }
            i = lastDigitEnd;
        }
        return out.toString();
    }

    private static boolean isSeparator(int c) {
        return c == ' ' || c == '.' || c == '-' || c == '(' || c == ')' || c == 0x00A0 || c == 0x202F;
    }
}
