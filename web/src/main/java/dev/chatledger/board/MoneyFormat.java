package dev.chatledger.board;

import org.springframework.stereotype.Component;

import java.util.OptionalLong;

/**
 * Money on a page and in a file: integers in minor units, formatted by integer arithmetic only (never a float or a {@code BigDecimal} that
 * started as one). Registered as a bean named {@code money} for templates.
 */
@Component("money")
public final class MoneyFormat {

    /** "5,000 XAF" (scale 0) or "50.25 EUR" (scale 2). A currency that is not three capital letters is left out. */
    public String format(long minor, int scale, String currency) {
        String code = currency != null && currency.length() == 3 && currency.chars().allMatch(c -> c >= 'A' && c <= 'Z') ? " " + currency : "";
        return group(plain(minor, scale)) + code;
    }

    /** "5000" or "50.25" or "-3.05": no grouping, for files. */
    public static String plain(long minor, int scale) {
        if (scale < 0 || scale > 6) {
            throw new IllegalArgumentException("scale out of range");
        }
        boolean negative = minor < 0;
        String digits = negative ? Long.toString(minor).substring(1) : Long.toString(minor);
        if (scale > 0) {
            while (digits.length() <= scale) {
                digits = "0" + digits;
            }
            digits = digits.substring(0, digits.length() - scale) + "." + digits.substring(digits.length() - scale);
        }
        return negative ? "-" + digits : digits;
    }

    private static String group(String plain) {
        boolean negative = plain.startsWith("-");
        String body = negative ? plain.substring(1) : plain;
        int dot = body.indexOf('.');
        String whole = dot < 0 ? body : body.substring(0, dot);
        String fraction = dot < 0 ? "" : body.substring(dot);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) {
                sb.append(',');
            }
            sb.append(whole.charAt(i));
        }
        return (negative ? "-" : "") + sb + fraction;
    }

    /**
     * An amount typed by a person, in major units, to minor units: digits, and a dot with at most {@code scale} decimals when the scale is
     * above zero. Nothing else (no sign, no grouping, no exponent, no letters, no spaces), at most 15 digits. Empty if it is not that.
     */
    public static OptionalLong parse(String text, int scale) {
        if (text == null || scale < 0 || scale > 6) {
            return OptionalLong.empty();
        }
        String s = text.strip();
        if (s.isEmpty() || s.length() > 16) {
            return OptionalLong.empty();
        }
        int dot = s.indexOf('.');
        String whole = dot < 0 ? s : s.substring(0, dot);
        String fraction = dot < 0 ? "" : s.substring(dot + 1);
        if (dot >= 0 && (fraction.isEmpty() || fraction.length() > scale)) {
            return OptionalLong.empty();
        }
        if (whole.isEmpty() || whole.length() > 15 || !ascii(whole) || !ascii(fraction)) {
            return OptionalLong.empty();
        }
        StringBuilder padded = new StringBuilder(fraction);
        while (padded.length() < scale) {
            padded.append('0');
        }
        String all = whole + padded;
        if (all.length() > 18) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Long.parseLong(all));
    }

    private static boolean ascii(String digits) {
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
