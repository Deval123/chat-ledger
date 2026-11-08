package dev.chatledger.matching;

import dev.chatledger.extraction.TextKeys;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares a sender's name with a customer's name. Both are free text (an SMS and a chat message), so the comparison is bounded and
 * built from plain loops: at most {@value #MAX_TOKENS} tokens of at most {@value #MAX_TOKEN_CHARS} characters, compared with
 * {@code equals} and {@code startsWith}. There is no regular expression, no similarity library and no reflection, and the result is a
 * three-valued enum, never any part of a name.
 *
 * <p>Names go through {@link TextKeys} first (accent-insensitive, lower case). Apostrophes are dropped ({@code N'Diaye} is {@code ndiaye}); every other punctuation mark separates tokens ({@code Jean-Luc} is two).
 * The agreement is computed from the name with fewer tokens:
 * <ul>
 *   <li>{@link Agreement#FULL}: at least two tokens, every one equal to a different token of the other name (order does not matter);</li>
 *   <li>{@link Agreement#INITIAL}: every token matches a different token of the other name, some of them as a single-letter initial
 *       ({@code K.} for {@code Kamga}), and at least one is an exact token of three or more letters (so {@code Ana} alone qualifies,
 *       {@code Al} alone does not);</li>
 *   <li>{@link Agreement#NONE}: anything else, and always for the neutral name used for a customer known only by phone.</li>
 * </ul>
 */
public final class NameSimilarity {

    /** What the order service stores for a customer with a phone and no name. It must never count as a name. */
    public static final String NEUTRAL_NAME = "Unknown customer";

    static final int MAX_NAME_CHARS = 100;
    static final int MAX_TOKENS = 8;
    static final int MAX_TOKEN_CHARS = 40;

    public enum Agreement { NONE, INITIAL, FULL }

    private static final String NEUTRAL_KEY = TextKeys.key(NEUTRAL_NAME);

    private NameSimilarity() {
    }

    public static Agreement compare(String senderName, String customerName) {
        if (senderName == null || customerName == null || TextKeys.key(customerName).equals(NEUTRAL_KEY)) {
            return Agreement.NONE;
        }
        List<String> a = tokens(senderName);
        List<String> b = tokens(customerName);
        if (a.size() == b.size()) {
            // Same number of tokens: either name may hold the initials, so the answer must not depend on which is which.
            Agreement forward = agreement(a, b);
            Agreement backward = agreement(b, a);
            return forward.ordinal() >= backward.ordinal() ? forward : backward;
        }
        return a.size() < b.size() ? agreement(a, b) : agreement(b, a);
    }

    /** How well every token of {@code small} is found in {@code large}. */
    private static Agreement agreement(List<String> small, List<String> large) {
        boolean[] used = new boolean[large.size()];
        int initials = 0;
        boolean longExact = false;
        for (String s : small) {
            int at = indexOfExact(large, used, s);
            if (at >= 0) {
                used[at] = true;
                longExact |= s.length() >= 3;
                continue;
            }
            at = s.length() == 1 ? indexOfInitial(large, used, s) : -1;
            if (at < 0) {
                return Agreement.NONE;
            }
            used[at] = true;
            initials++;
        }
        if (initials == 0 && small.size() >= 2) {
            return Agreement.FULL;
        }
        return longExact ? Agreement.INITIAL : Agreement.NONE;
    }

    private static int indexOfExact(List<String> large, boolean[] used, String s) {
        for (int i = 0; i < large.size(); i++) {
            if (!used[i] && large.get(i).equals(s)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfInitial(List<String> large, boolean[] used, String s) {
        for (int i = 0; i < large.size(); i++) {
            if (!used[i] && large.get(i).startsWith(s)) {
                return i;
            }
        }
        return -1;
    }

    static List<String> tokens(String name) {
        String key = TextKeys.key(name.length() > MAX_NAME_CHARS ? name.substring(0, MAX_NAME_CHARS) : name);
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i <= key.length() && out.size() < MAX_TOKENS; i++) {
            char c = i < key.length() ? key.charAt(i) : ' ';
            if (c == '\'' || c == '\u2019') {
                continue;   // N'Diaye and NDiaye are the same name
            }
            if (Character.isLetterOrDigit(c)) {
                if (current.length() < MAX_TOKEN_CHARS) {
                    current.append(c);
                }
            } else if (current.length() > 0) {
                out.add(current.toString());
                current.setLength(0);
            }
        }
        return out;
    }
}
