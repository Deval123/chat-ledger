package dev.chatledger.extraction;

import java.util.Locale;

/**
 * Reads one money amount, with an optional currency word before or after it, into integer minor units. Strict:
 * a number that could mean two things is refused, not guessed.
 *
 * <ul>
 *   <li>Currency with scale 0 (XAF): digits with optional thousands grouping. One separator character is used
 *       throughout, one of space, comma, dot or apostrophe, and every group after the first has exactly three
 *       digits ({@code 6000}, {@code 6 000}, {@code 6,000}, {@code 6.000}).</li>
 *   <li>Currency with decimals: either whole units ({@code 12}) or a decimal part of exactly {@code scale} digits
 *       after a dot or comma ({@code 12.50}); only a space may group thousands. {@code 1,500} is refused (it could
 *       be fifteen hundred or one and a half).</li>
 *   <li>No leading zeros, 13 digits at most, at least 1 minor unit, at most {@link OrderLimits#MAX_MINOR}.</li>
 * </ul>
 */
final class AmountParser {

    record Amount(long minor, String currency) {
    }

    /** Either an amount or the reason it could not be read. */
    record Result(Amount amount, FailureReason failure) {
        static Result ok(Amount a) {
            return new Result(a, null);
        }

        static Result fail(FailureReason r) {
            return new Result(null, r);
        }
    }

    private AmountParser() {
    }

    static Result parse(String text, int scale) {
        String s = text == null ? "" : text.strip();
        int first = -1;
        int last = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0) {
            return Result.fail(FailureReason.INVALID_AMOUNT);
        }
        String prefix = s.substring(0, first).strip();
        String number = s.substring(first, last + 1);
        String suffix = s.substring(last + 1).strip();
        if (!prefix.isEmpty() && !suffix.isEmpty()) {
            return Result.fail(FailureReason.INVALID_AMOUNT);
        }
        String currencyWord = prefix.isEmpty() ? suffix : prefix;
        String currency = null;
        if (!currencyWord.isEmpty()) {
            currency = currency(currencyWord);
            if (currency == null) {
                return Result.fail(FailureReason.INVALID_AMOUNT);
            }
        }
        return minor(number, scale, currency);
    }

    /** The tenant-currency code for a currency word, a three-letter code as written, or null. */
    static String currency(String word) {
        String upper = word.strip().toUpperCase(Locale.ROOT);
        String alias = OrderLabels.CURRENCY_ALIASES.get(upper);
        if (alias != null) {
            return alias;
        }
        if (upper.length() == 3) {
            for (int i = 0; i < 3; i++) {
                char c = upper.charAt(i);
                if (c < 'A' || c > 'Z') {
                    return null;
                }
            }
            return upper;
        }
        return null;
    }

    private static Result minor(String number, int scale, String currency) {
        int digitCount = 0;
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c >= '0' && c <= '9') {
                digitCount++;
            } else if (c != ' ' && c != ',' && c != '.' && c != '\'') {
                return Result.fail(FailureReason.INVALID_AMOUNT);
            }
        }
        if (digitCount > OrderLimits.MAX_AMOUNT_DIGITS) {
            return Result.fail(FailureReason.AMOUNT_TOO_LARGE);
        }

        String integerPart = number;
        String fraction = "";
        if (scale > 0) {
            int lastSep = Math.max(number.lastIndexOf('.'), number.lastIndexOf(','));
            if (lastSep >= 0 && number.length() - lastSep - 1 == scale && allDigits(number.substring(lastSep + 1))) {
                integerPart = number.substring(0, lastSep);
                fraction = number.substring(lastSep + 1);
                if (integerPart.isEmpty()) {
                    return Result.fail(FailureReason.INVALID_AMOUNT);
                }
            }
        }   // with decimals only a space may group thousands (see ungroup below)

        String units = ungroup(integerPart, scale > 0 ? ' ' : (char) 0, scale == 0);
        if (units == null) {
            return Result.fail(FailureReason.INVALID_AMOUNT);
        }
        if (units.length() > 1 && units.charAt(0) == '0') {
            return Result.fail(FailureReason.INVALID_AMOUNT);
        }
        try {
            long whole = Long.parseLong(units);
            long scaleFactor = 1;
            for (int i = 0; i < scale; i++) {
                scaleFactor = Math.multiplyExact(scaleFactor, 10L);
            }
            long minor = Math.addExact(Math.multiplyExact(whole, scaleFactor),
                    fraction.isEmpty() ? 0L : Long.parseLong(fraction));
            if (minor < 1) {
                return Result.fail(FailureReason.INVALID_AMOUNT);
            }
            if (minor > OrderLimits.MAX_MINOR) {
                return Result.fail(FailureReason.AMOUNT_TOO_LARGE);
            }
            return Result.ok(new Amount(minor, currency));
        } catch (ArithmeticException | NumberFormatException e) {
            return Result.fail(FailureReason.AMOUNT_TOO_LARGE);
        }
    }

    /**
     * The digits of a possibly grouped integer, or null if the grouping is not regular. With {@code anySeparator}
     * the one separator used may be a space, comma, dot or apostrophe; otherwise only {@code only} (when not 0) or none.
     */
    private static String ungroup(String s, char only, boolean anySeparator) {
        char separator = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                continue;
            }
            if (anySeparator ? !(c == ' ' || c == ',' || c == '.' || c == '\'') : c != only) {
                return null;
            }
            if (separator == 0) {
                separator = c;
            } else if (separator != c) {
                return null;
            }
        }
        if (separator == 0) {
            return allDigits(s) && !s.isEmpty() ? s : null;
        }
        // Walk the string once, closing a group at each separator (no pattern is built from the input).
        StringBuilder digits = new StringBuilder();
        int groupStart = 0;
        boolean firstGroup = true;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == separator) {
                int length = i - groupStart;
                boolean ok = firstGroup ? length >= 1 && length <= 3 : length == 3;
                if (!ok) {
                    return null;
                }
                digits.append(s, groupStart, i);
                groupStart = i + 1;
                firstGroup = false;
            }
        }
        return allDigits(digits.toString()) ? digits.toString() : null;
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
