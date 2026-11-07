package dev.chatledger.extraction;

/**
 * Answers one question: is this amount written in this text? It looks at every run of digits (digits joined by single
 * separators such as a space, comma, dot or apostrophe) and compares the digits, so it does not depend on the parser
 * that found the amount; it is an independent check of it.
 *
 * <p>Limit, named: it proves the number is written somewhere in the message, not that it is the price. A price that
 * happens to equal a phone number's digits would pass; the stated-total versus lines check and the seller's review
 * are the other defenses.
 */
final class SourceAmounts {

    private SourceAmounts() {
    }

    static boolean contains(String normalizedText, long minor, int scale) {
        String wanted = Long.toString(minor);
        String wholeUnits = null;
        if (scale > 0 && minor % pow10(scale) == 0) {
            wholeUnits = Long.toString(minor / pow10(scale));
        }
        int n = normalizedText.length();
        int i = 0;
        while (i < n) {
            if (!isDigit(normalizedText.charAt(i))) {
                i++;
                continue;
            }
            StringBuilder digits = new StringBuilder();
            int j = i;
            while (j < n) {
                char c = normalizedText.charAt(j);
                if (isDigit(c)) {
                    digits.append(c);
                    j++;
                } else if (isSeparator(c) && j + 1 < n && isDigit(normalizedText.charAt(j + 1))) {
                    j++;
                } else {
                    break;
                }
            }
            String run = digits.toString();
            if (run.equals(wanted) || (wholeUnits != null && run.equals(wholeUnits))) {
                return true;
            }
            i = j;
        }
        return false;
    }

    private static long pow10(int scale) {
        long p = 1;
        for (int i = 0; i < scale; i++) {
            p *= 10;
        }
        return p;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == ',' || c == '.' || c == '\'';
    }
}
