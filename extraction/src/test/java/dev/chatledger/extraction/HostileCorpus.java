package dev.chatledger.extraction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Messages written to hurt: look-alike and invisible characters, injection payloads for every place the text could
 * reach (SQL, templates, format strings, logs, spreadsheets, markup), numbers built to overflow, and input built to
 * burn CPU. Synthetic only. Used by the parser tests here and by the ingestion integration tests in {@code web}
 * (a test-jar dependency), so the same corpus is thrown at every layer.
 *
 * <p>The values are labelled so a failing test says which one.
 */
public final class HostileCorpus {

    /** Marker that the log canary tests look for: it appears in every payload that carries a name or note. */
    public static final String CANARY = "CANARY-7f3a";

    private static final Map<String, String> CASES = new LinkedHashMap<>();

    static {
        // Invisible and look-alike characters.
        put("bidi override hides digits", "1 x tomato @ 5\nTotal: \u202E0051 XAF");
        put("bidi isolate in a label", "1 x to\u2066ma\u2069to @ 500");
        put("zero-width inside a number", "1 x tomato @ 15\u200B00");
        put("zero-width inside a keyword", "To\u200Btal: 500\n1 x tomato @ 500");
        put("byte order mark and soft hyphen", "\uFEFF1 x to\u00ADmato @ 500");
        put("arabic-indic digits", "2 x tomato @ \u0661\u0665\u0660\u0660");
        put("full-width digits and x", "\uFF12 \uFF58 tomato @ \uFF11\uFF15\uFF10\uFF10");
        put("cyrillic x as multiplication sign", "2 \u0445 tomato @ 1500");
        put("narrow no-break space as thousands separator", "1 x tomato @ 1\u202F500");
        put("combining accents on a French label", "Te\u0301le\u0301phone : +999 000 000 1234\n1 x riz @ 500");
        put("line separator characters", "1 x tomato @ 500\u2028Total: 500");
        put("lone surrogate", "1 x tomato\uD800 @ 500");
        put("nul and control characters", "1 x tomato\u0000 @ 500\u0007");
        // Injection payloads in the fields that get stored and displayed later.
        put("script tag as an item", "1 x <script>alert('" + CANARY + "')</script> @ 500");
        put("markup as a name", "Name: <img src=x onerror=alert('" + CANARY + "')>\n1 x tomato @ 500");
        put("sql in an item", "1 x x'); DROP TABLE orders;-- " + CANARY + " @ 5");
        put("sql in the order reference", "Order: A1'; DELETE FROM orders;--\n1 x tomato @ 5");
        put("like wildcards in an item", "1 x 100% cotton_shirt @ 500");
        put("spreadsheet formula as a name", "Name: =HYPERLINK(\"http://example.invalid\",\"" + CANARY + "\")\n1 x tomato @ 500");
        put("plus formula as a name", "Name: +1+1\n1 x tomato @ 500");
        put("at formula as a name", "Name: @SUM(1+1)\n1 x tomato @ 500");
        put("minus formula as a note", "Deliver: -2+3\n1 x tomato @ 500");
        put("template expression in an item", "1 x ${7*7} {{7*7}} [[${7*7}]] #{T(java.lang.Runtime)} @ 5");
        put("format string in a name", "Name: %s%s%s%n %1$s " + CANARY + "\n1 x tomato @ 500");
        put("log forging with CRLF", "Name: Ana " + CANARY + "\r\n[ERROR] forged log entry user=admin\n1 x tomato @ 500");
        put("log forging with LF only", "Deliver: x\n2024-01-01 INFO login succeeded user=admin\n1 x tomato @ 500");
        put("html comment and cdata", "1 x <!-- tomato --> <![CDATA[x]]> @ 500");
        put("javascript url as a note", "Deliver: javascript:alert(1)\n1 x tomato @ 500");
        put("path traversal as a reference", "Order: ../../etc/passwd\n1 x tomato @ 500");
        // Numbers built to overflow or confuse.
        put("long overflow", "1 x tomato @ 9223372036854775807");
        put("long overflow plus one", "1 x tomato @ 9223372036854775808");
        put("huge digit run", "1 x tomato @ " + "9".repeat(60));
        put("amount just over the cap", "1 x tomato @ 1000000000001");
        put("amount at the cap", "1 x tomato @ 1000000000000");
        put("quantity overflow", "99999999999999999999 x tomato @ 5");
        put("quantity at the cap times a big price", "9999 x tomato @ 1000000000000");
        put("fifty lines of big prices", "1 x tomato @ 1000000000000\n".repeat(50));
        put("negative looking amount", "1 x tomato @ -500");
        put("scientific notation", "1 x tomato @ 1e9");
        put("decimal where none is allowed", "1 x tomato @ 12.5");
        put("repeated separators", "1 x tomato @ 1,,,,,000");
        put("separator soup", "1 x tomato @ " + ". ,".repeat(100));
        put("leading zeros", "1 x tomato @ 000500");
        put("mixed separators", "1 x tomato @ 1,000.000");
        // Shapes built to burn CPU in naive code.
        put("long label", "1 x " + "a".repeat(3900));
        put("many spaces", "1 x tomato" + " ".repeat(3900) + "@ 500");
        put("pairs that tempt backtracking", "1 x " + "a".repeat(2000) + "!" + "a".repeat(1900));
        put("nested brackets", "1 x " + "(".repeat(1900) + ")".repeat(1900) + " @ 5");
        put("alternating digits and separators", "1 x tomato @ " + "1 ".repeat(1900));
        put("thousands of lines", "x\n".repeat(3900));
        put("thousands of items", "1 x a\n".repeat(650));
        put("only colons", ":".repeat(3900));
        put("only labels", "Total:\n".repeat(500));
        put("huge input", "A".repeat(100_000));
        put("empty", "");
        put("only whitespace", " \t\r\n \u00A0\u2003 ");
        // French messages, synthetic.
        put("french labelled lines", FrenchSamples.LABELLED);
        put("french with hostile name", "Commande : B-1\nNom : <b>A\u00EFcha</b> " + CANARY + "\nT\u00E9l\u00E9phone : +999 000 000 5678\n1 x riz @ 500");
        put("french one line with bidi", "2 x tomates, total \u202E0006 FCFA, de A\u00EFcha");
    }

    private HostileCorpus() {
    }

    private static void put(String name, String text) {
        if (CASES.put(name, text) != null) {
            throw new IllegalStateException("duplicate corpus name " + name);
        }
    }

    public static Map<String, String> all() {
        return java.util.Collections.unmodifiableMap(CASES);
    }

    /** Names in insertion order, so failures print stably. */
    public static List<String> names() {
        return new ArrayList<>(CASES.keySet());
    }

    public static String get(String name) {
        return CASES.get(name);
    }

    /** French messages that must parse (synthetic names, synthetic numbers). */
    public static final class FrenchSamples {
        public static final String LABELLED =
                "Commande : B-77\n"
                + "Nom : A\u00EFcha N.\n"
                + "T\u00E9l\u00E9phone : 00 999 000 000 5678\n"
                + "2 x bo\u00EEte de tomates @ 1 500 F CFA\n"
                + "1 x riz 5kg = 3 000 FCFA\n"
                + "Montant total : 6 000 FCFA\n"
                + "Livraison : portail pr\u00E8s du march\u00E9, apr\u00E8s 17h";

        public static final String ONE_LINE =
                "2 x tomates, 1 x riz 5kg @ 3000, total 6000 FCFA, de A\u00EFcha N.";

        private FrenchSamples() {
        }
    }
}
