package dev.chatledger.extraction;

import java.util.Locale;

/**
 * The one key function for order references. An order's {@code order_ref} (what the seller or the order message wrote) and a
 * payment's {@code reference} (what the payer wrote in the transfer note) are compared only through {@link #key(String)}, so
 * {@code a-1042} and {@code A-1042} are the same reference and nothing else is.
 *
 * <p>References are stored as written (same format CHECK on both tables); matching (task 6) compares keys. The character set
 * of a reference is ASCII, so {@code upper(...)} in SQL and this function agree.
 */
public final class ReferenceKeys {

    private ReferenceKeys() {
    }

    /** The comparison key: trimmed and upper-cased; empty for null. */
    public static String key(String reference) {
        return reference == null ? "" : reference.strip().toUpperCase(Locale.ROOT);
    }

    /** True if both references are present and have the same key. */
    public static boolean same(String a, String b) {
        String ka = key(a);
        return !ka.isEmpty() && ka.equals(key(b));
    }
}
