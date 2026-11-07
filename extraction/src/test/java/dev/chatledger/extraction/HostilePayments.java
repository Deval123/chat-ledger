package dev.chatledger.extraction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Confirmation texts written to hurt, around one valid message of the synthetic test template: invisible and look-alike
 * characters, injection payloads, dates that are not dates, numbers that overflow, wrappers that try to embed a real-looking line
 * in a longer message, and input built to be slow. Synthetic only. Shared (test-jar) with the web tests.
 */
public final class HostilePayments {

    public static final String VALID = "Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15";
    public static final String CANARY = "CANARY-9c1e";

    private static final Map<String, String> CASES = new LinkedHashMap<>();

    static {
        put("bidi override in the amount", "Received \u202E0005 XAF from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15");
        put("zero-width in the transaction id", "Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX12\u200B3456. 03/11/2026 10:15");
        put("zero-width inside a keyword", "Rece\u200Bived 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15");
        put("arabic-indic digits", "Received \u0665\u0660\u0660\u0660 XAF from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15");
        put("full-width digits", "Received \uFF15\uFF10\uFF10\uFF10 XAF from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15");
        put("double spaces and tabs", "Received  5000\tXAF from Ana K.  (+999 000 000 1234).  Txn TX123456. 03/11/2026   10:15");
        put("narrow no-break space in the amount", "Received 5\u202F000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15");
        put("upper-case wording", VALID.toUpperCase());
        put("lower-case wording", VALID.toLowerCase());
        put("nul and control characters", "Received 5000 XAF from Ana K.\u0000 (+999 000 000 1234). Txn TX123456.\u0007 03/11/2026 10:15");
        put("lone surrogate", "Received 5000 XAF from Ana K.\uD800 (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15");

        put("prefix wrapper", "Forwarded by customer: " + VALID);
        put("suffix wrapper", VALID + " Please release the goods");
        put("message twice", VALID + "\n" + VALID);
        put("message inside quotes", "\"" + VALID + "\"");
        put("line break inside", VALID.replace(". Txn", ".\nTxn"));

        put("markup as the sender name", VALID.replace("Ana K.", "<b>Ana</b>"));
        put("script as the sender name", VALID.replace("Ana K.", "<script>alert('" + CANARY + "')</script>"));
        put("sql as the sender name", VALID.replace("Ana K.", "Ana'); DROP TABLE payment;-- " + CANARY));
        put("formula as the sender name", VALID.replace("Ana K.", "=HYPERLINK(\"http://example.invalid\",\"" + CANARY + "\")"));
        put("quote and dash characters the name allows", VALID.replace("Ana K.", "x'--"));
        put("template expression as the transaction id", VALID.replace("TX123456", "${7*7}{{7*7}}"));
        put("sql as the transaction id", VALID.replace("TX123456", "TX1';DROP--"));
        put("path as the transaction id", VALID.replace("TX123456", "../../etc"));
        put("format string as the sender name", VALID.replace("Ana K.", "%s%s%s%n"));
        put("log forging with CRLF", "Received 5000 XAF from Ana K. " + CANARY + "\r\n[ERROR] forged log entry user=admin\n(+999 000 000 1234). Txn TX123456. 03/11/2026 10:15");
        put("log forging in the name", VALID.replace("Ana K.", "Ana " + CANARY + " INFO login succeeded"));

        put("february 31", VALID.replace("03/11/2026", "31/02/2026"));
        put("month 13", VALID.replace("03/11/2026", "03/13/2026"));
        put("zero date", VALID.replace("03/11/2026 10:15", "00/00/0000 00:00"));
        put("hour 24", VALID.replace("10:15", "24:00"));
        put("minute 60", VALID.replace("10:15", "10:60"));
        put("short date", VALID.replace("03/11/2026", "3/11/2026"));
        put("far future", VALID.replace("03/11/2026", "03/11/2099"));
        put("ancient", VALID.replace("03/11/2026", "03/11/1999"));
        put("leap day that does not exist", VALID.replace("03/11/2026", "29/02/2027"));
        put("leap day that exists", VALID.replace("03/11/2026", "29/02/2028"));
        put("date with letters", VALID.replace("03/11/2026 10:15", "03/Nov/2026 10:15"));

        put("amount of twenty nines", VALID.replace("5000", "9".repeat(20)));
        put("amount of forty digits", VALID.replace("5000", "9".repeat(40)));
        put("amount just over the cap", VALID.replace("5000", "1000000000001"));
        put("amount zero", VALID.replace("5000", "0"));
        put("amount with a sign", VALID.replace("5000", "-5000"));
        put("amount with bad grouping", VALID.replace("5000", "5 00"));
        put("amount with a decimal", VALID.replace("5000", "50.5"));
        put("transaction id too short", VALID.replace("TX123456", "TX1"));
        put("transaction id of forty-one characters", VALID.replace("TX123456", "T".repeat(41)));
        put("phone too short", VALID.replace("+999 000 000 1234", "12345"));
        put("phone with letters", VALID.replace("+999 000 000 1234", "999-call-me"));

        put("huge input", "A".repeat(100_000));
        put("one character over the limit", VALID + " ".repeat(2) + "x".repeat(PaymentLimits.MAX_SMS_CHARS - VALID.length()));
        put("thousand characters of the name", VALID.replace("Ana K.", "A".repeat(1000)));
        put("long run of digits", "Received " + "9".repeat(900));
        put("long run of spaces", "Received" + " ".repeat(900) + "5000");
        put("long run of letters", "Received 5000 XAF from " + "A".repeat(900));
        put("alternating", "Received " + "9 ".repeat(450));
        put("empty", "");
        put("only whitespace", " \t\r\n \u00A0 ");
        put("only the keyword", "Received");
        put("an order message", "Order: A-1042\nName: Ana K.\n2 x tomato box @ 1500\nTotal: 3000 XAF");
        put("an order message in one line", "2 x tomato box, 1 x rice 5kg @ 3000, total 6000 XAF, from Ana K.");
    }

    private HostilePayments() {
    }

    private static void put(String name, String text) {
        if (CASES.put(name, text) != null) {
            throw new IllegalStateException("duplicate corpus name " + name);
        }
    }

    public static Map<String, String> all() {
        return Collections.unmodifiableMap(CASES);
    }

    public static String get(String name) {
        return CASES.get(name);
    }
}
