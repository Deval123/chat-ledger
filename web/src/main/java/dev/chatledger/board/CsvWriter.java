package dev.chatledger.board;

import java.util.List;

/**
 * CSV for spreadsheets, safe to open. A cell that holds <em>text</em> (anything that came from a message: a name, a label, a reference, a note,
 * an account name) is cleaned and, if a spreadsheet could read it as a formula, prefixed with a single quote so that it is read as text. A
 * cell the program writes from a number is not text and is never prefixed, so a real {@code -500} stays a number.
 *
 * <p>A text cell is "formula-like" when its first character after any leading white space or control characters is one of
 * {@code = + - @ |}, their full-width forms, or when it starts with a tab, carriage return or line feed (which some programs skip over).
 * Text cells are always double-quoted with inner quotes doubled; lines end with CRLF (RFC 4180). The quote prefix changes the cell, which is
 * said on the download page.
 */
public final class CsvWriter {

    private static final String FORMULA_STARTS = "=+-@|\uFF1D\uFF0B\uFF0D\uFF20";

    private CsvWriter() {
    }

    /** A text cell: cleaned, neutralised, quoted. {@code null} is an empty text cell. */
    public static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "\"\"";
        }
        boolean startsWithBreak = value.charAt(0) == '\t' || value.charAt(0) == '\r' || value.charAt(0) == '\n';
        String cleaned = clean(value);
        boolean formula = startsWithBreak || firstSignificantIsFormulaStart(cleaned);
        String cell = formula ? "'" + cleaned : cleaned;
        return "\"" + cell.replace("\"", "\"\"") + "\"";
    }

    /** Skips leading white space of every kind (also the no-break space, which Java does not call white space) and looks at the first real character. */
    private static boolean firstSignificantIsFormulaStart(String cleaned) {
        for (int i = 0; i < cleaned.length(); ) {
            int cp = cleaned.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                continue;
            }
            return cp < 0x10000 && FORMULA_STARTS.indexOf((char) cp) >= 0;
        }
        return false;
    }

    /** A cell the program wrote from an integer in minor units (see {@link MoneyFormat#plain}) or a count: never prefixed. */
    public static String number(long minor, int scale) {
        return MoneyFormat.plain(minor, scale);
    }

    public static String integer(long value) {
        return Long.toString(value);
    }

    /** One line: the cells already formatted by this class, joined by commas, ending CRLF. */
    public static String line(List<String> cells) {
        return String.join(",", cells) + "\r\n";
    }

    /** Control characters, format characters (bidirectional overrides, zero-width), private-use and lone surrogates removed; line breaks and tabs become spaces. */
    static String clean(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (cp == '\t' || cp == '\r' || cp == '\n' || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR || cp == 0x85) {
                sb.append(' ');
            } else if (type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.SURROGATE
                    || type == Character.UNASSIGNED) {
                continue;
            } else {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }
}
