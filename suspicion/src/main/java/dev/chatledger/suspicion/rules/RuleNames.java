package dev.chatledger.suspicion.rules;

import dev.chatledger.extraction.TextKeys;
import dev.chatledger.matching.NameSimilarity;

/** Name comparisons shared by R8 and R10. Bounded (through {@link NameSimilarity} and {@link TextKeys}), no regular expression. */
final class RuleNames {
    private static final String NEUTRAL_KEY = TextKeys.key(NameSimilarity.NEUTRAL_NAME);

    private RuleNames() {
    }

    /** A name worth comparing: present, not blank, and not the neutral name stored for a customer known only by phone. */
    static boolean usable(String name) {
        if (name == null) {
            return false;   // a blank name has an empty key, refused below
        }
        String key = TextKeys.key(name);
        return !key.isEmpty() && !key.equals(NEUTRAL_KEY);
    }

    /** True when the two names are the same once normalised, or agree in full or by initial. */
    static boolean agree(String a, String b) {
        return TextKeys.key(a).equals(TextKeys.key(b)) || NameSimilarity.compare(a, b) != NameSimilarity.Agreement.NONE;
    }
}
