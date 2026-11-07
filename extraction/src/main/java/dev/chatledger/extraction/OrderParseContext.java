package dev.chatledger.extraction;

/**
 * What the parser needs to know about the tenant: its currency (an ISO code) and how many decimal places that
 * currency has. Both come from the tenant's row, never from the message.
 */
public record OrderParseContext(String currency, int scale) {

    public OrderParseContext {
        if (currency == null || currency.length() != 3 || !isUpperLetter(currency.charAt(0))
                || !isUpperLetter(currency.charAt(1)) || !isUpperLetter(currency.charAt(2))) {
            throw new IllegalArgumentException("currency must be a three-letter upper-case code");
        }
        if (scale < 0 || scale > 3) {
            throw new IllegalArgumentException("scale must be between 0 and 3");
        }
    }

    private static boolean isUpperLetter(char c) {
        return c >= 'A' && c <= 'Z';
    }
}
