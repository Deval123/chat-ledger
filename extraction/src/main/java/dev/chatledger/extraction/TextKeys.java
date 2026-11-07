package dev.chatledger.extraction;

import java.text.Normalizer;
import java.util.Locale;

/**
 * A comparison key for short labels: accent-insensitive, case-insensitive, single-spaced. Used to recognise the
 * field labels of a message ({@code Téléphone} equals {@code telephone}) and as the catalog key of an item name.
 * It is only ever used for equality, never inside a pattern.
 */
public final class TextKeys {

    private TextKeys() {
    }

    public static String key(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        boolean pendingSpace = false;
        for (int i = 0; i < decomposed.length(); ) {
            int cp = decomposed.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.FORMAT) {
                continue;
            }
            if (Character.isWhitespace(cp) || type == Character.SPACE_SEPARATOR || Character.isISOControl(cp)) {
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }
            sb.appendCodePoint(Character.toLowerCase(cp));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
