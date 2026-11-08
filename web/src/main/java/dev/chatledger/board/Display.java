package dev.chatledger.board;

import org.springframework.stereotype.Component;

/**
 * The one way text that came from outside (a name, a label, a reference, a note, a message) reaches a page. It cleans for display and cuts;
 * it never changes what is stored. A template then prints the result with {@code th:text}, which escapes it.
 *
 * <p>Removed: control characters, the "format" characters (bidirectional overrides and isolates, zero-width characters, the byte-order
 * mark, the soft hyphen), private-use and unassigned code points, lone surrogates, and combining marks beyond the second in a row (the
 * stacked-marks trick that makes a line grow out of its box). Every kind of white space (including line and paragraph separators) becomes
 * one plain space; runs collapse; the ends are trimmed. The cut is by code points, never inside a surrogate pair, and adds an ellipsis.
 *
 * <p>Registered as a bean named {@code display} so a template can say {@code ${@display.text(x, 40)}}.
 */
@Component("display")
public final class Display {

    /** The ellipsis added to a cut text. */
    static final String ELLIPSIS = "\u2026";

    /** One line: all white space becomes a single space. */
    public String text(String value, int max) {
        return clean(value, max, false);
    }

    /** Several lines: line breaks are kept (at most one blank line in a row), other white space collapses. For messages and notes. */
    public String block(String value, int max) {
        return clean(value, max, true);
    }

    static String clean(String value, int max, boolean keepLines) {
        if (value == null || max < 1) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), max + 1));
        int kept = 0;
        int marks = 0;
        int pendingBreaks = 0;
        boolean pendingSpace = false;
        boolean cut = false;
        boolean afterCr = false;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            boolean lineBreak = cp == '\n' || cp == '\r' || cp == 0x85 || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
            boolean space = cp == ' ' || cp == '\t' || cp == 0x0B || cp == 0x0C || type == Character.SPACE_SEPARATOR;
            boolean secondOfCrLf = cp == '\n' && afterCr;
            afterCr = cp == '\r';
            if (lineBreak && keepLines) {
                if (!secondOfCrLf) {   // a CRLF is one line break
                    pendingBreaks++;   // one or two or more: shown as one line break or one blank line
                }
                continue;
            }
            if (lineBreak || space) {
                pendingSpace = true;
                continue;
            }
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.SURROGATE
                    || type == Character.UNASSIGNED) {
                continue;
            }
            boolean mark = type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK;
            if (mark && marks >= 2) {
                continue;
            }
            if (kept > 0 && pendingBreaks > 0) {
                out.append(pendingBreaks == 1 ? "\n" : "\n\n");
                kept += 1;
            } else if (kept > 0 && pendingSpace) {
                out.append(' ');
                kept += 1;
            }
            pendingBreaks = 0;
            pendingSpace = false;
            if (kept >= max) {
                cut = true;
                break;
            }
            out.appendCodePoint(cp);
            kept += 1;
            marks = mark ? marks + 1 : 0;
        }
        // the separator written before the cut counts toward the length; do not end on one
        String result = out.toString();
        if (cut) {
            result = result.stripTrailing() + ELLIPSIS;
        }
        return result;
    }
}
