package dev.chatledger.extraction;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes message text comparable before it is parsed. It produces a derived copy; the original text is stored as
 * it arrived and is never replaced by this.
 *
 * <ul>
 *   <li>Format characters (right-to-left overrides, zero-width characters, soft hyphen, byte order mark) are removed.
 *       Left in, they can make {@code 1500} display as {@code 0051} or hide text from a reader.</li>
 *   <li>Unicode compatibility normalisation (NFKC): full-width digits and letters become ordinary ones.</li>
 *   <li>Every decimal digit of any script (for example Arabic-Indic) becomes an ASCII digit.</li>
 *   <li>All spaces (non-breaking, narrow, ideographic) become one ordinary space; runs of spaces collapse;
 *       line breaks of any kind become {@code \n}; empty lines are dropped; control characters and lone surrogates
 *       become a space.</li>
 * </ul>
 *
 * <p>The result is stable: normalising it again changes nothing (checked by a property test), so a stored copy and a
 * recomputed copy always agree.
 */
public final class TextNormalizer {

    private static final int MAX_PASSES = 4;

    private TextNormalizer() {
    }

    public static String normalize(String raw) {
        String current = raw == null ? "" : raw;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            String next = onePass(current);
            if (next.equals(current)) {
                return next;
            }
            current = next;
        }
        return current;
    }

    private static String onePass(String text) {
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); ) {
            int cp = nfkc.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.FORMAT) {
                continue;
            }
            if (type == Character.DECIMAL_DIGIT_NUMBER) {
                sb.append((char) ('0' + Character.digit(cp, 10)));
            } else if (cp == '\r') {
                sb.append('\n');
            } else if (cp == '\n' || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                sb.append('\n');
            } else if (Character.isWhitespace(cp) || type == Character.SPACE_SEPARATOR || Character.isISOControl(cp)
                    || type == Character.SURROGATE || type == Character.UNASSIGNED || type == Character.PRIVATE_USE) {
                sb.append(' ');
            } else {
                sb.appendCodePoint(cp);
            }
        }
        return tidyLines(sb.toString());
    }

    /** Collapses runs of spaces, strips each line, drops empty lines. A "\r\n" pair already became two breaks. */
    private static String tidyLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            StringBuilder sb = new StringBuilder(line.length());
            boolean space = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == ' ') {
                    space = sb.length() > 0;
                } else {
                    if (space) {
                        sb.append(' ');
                        space = false;
                    }
                    sb.append(c);
                }
            }
            if (sb.length() > 0) {
                lines.add(sb.toString());
            }
        }
        return String.join("\n", lines);
    }
}
