package dev.chatledger.extraction;

import java.util.List;

/** Small synthetic template specs for the tests. All names and numbers are invented. */
public final class TestSpecs {

    public static final String GOOD = "Received {amount} XAF from {senderName} ({senderPhone}). Txn {transactionId}. {paidAt}";
    public static final String GOOD_REF = "Received {amount} XAF from {senderName} ({senderPhone}). Txn {transactionId}. Ref {reference}. {paidAt}";

    private TestSpecs() {
    }

    /** A spec that declares a transaction id exactly when the template has a {transactionId} hole. */
    static PaymentTemplateSpec bare(String template) {
        boolean tx = template != null && template.contains("{transactionId}");
        return new PaymentTemplateSpec("t-1", "PROVIDER_T", "XAF", 0, tx, "+01:00", "dd/MM/yyyy HH:mm", template,
                List.of(), List.of());
    }

    static PaymentTemplateSpec with(String id, String provider, String currency, int scale, boolean tx, String zone, String date,
                                    String template) {
        return new PaymentTemplateSpec(id, provider, currency, scale, tx, zone, date, template, List.of(), List.of());
    }

    public static PaymentTemplateSpec.Example example(String text, long amount, String tx, String name, String last4, String ref, String paid) {
        return new PaymentTemplateSpec.Example(text, new PaymentTemplateSpec.Expect(amount, tx, name, last4, ref, paid));
    }

    /** A complete, valid spec: three examples and five counter-examples. */
    public static PaymentTemplateSpec good() {
        return new PaymentTemplateSpec("good", "PROVIDER_T", "XAF", 0, true, "+01:00", "dd/MM/yyyy HH:mm", GOOD,
                List.of(
                        example("Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15", 5000, "TX123456",
                                "Ana K.", "1234", null, "2026-11-03T09:15:00Z"),
                        example("Received 12 500 XAF from Bea (999 000 000 5678). Txn TX-000-77. 02/11/2026 18:40", 12500, "TX-000-77",
                                "Bea", "5678", null, "2026-11-02T17:40:00Z"),
                        example("Received 700 XAF from Cy O'Neil (+999 000 000 4321). Txn TX99999Z. 03/11/2026 11:05", 700, "TX99999Z",
                                "Cy O'Neil", "4321", null, "2026-11-03T10:05:00Z")),
                List.of(
                        "Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15 thanks",
                        "Hi. Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15",
                        "Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456.",
                        "Sent 5000 XAF to Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15",
                        "Received 5000 XAF from Ana K. (+999 000 000 1234). Txn AB. 03/11/2026 10:15"));
    }

    /** The same shape with a reference; its examples are not matched by {@link #good()} and the reverse. */
    public static PaymentTemplateSpec goodRef() {
        return new PaymentTemplateSpec("good-ref", "PROVIDER_T", "XAF", 0, true, "+01:00", "dd/MM/yyyy HH:mm", GOOD_REF,
                List.of(
                        example("Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. Ref A-1042. 03/11/2026 10:15", 5000,
                                "TX123456", "Ana K.", "1234", "A-1042", "2026-11-03T09:15:00Z"),
                        example("Received 12 500 XAF from Bea (999 000 000 5678). Txn TX-000-77. Ref ORD_7. 02/11/2026 18:40", 12500,
                                "TX-000-77", "Bea", "5678", "ORD_7", "2026-11-02T17:40:00Z"),
                        example("Received 700 XAF from Cy O'Neil (+999 000 000 4321). Txn TX99999Z. Ref b12. 03/11/2026 11:05", 700,
                                "TX99999Z", "Cy O'Neil", "4321", "b12", "2026-11-03T10:05:00Z")),
                List.of(
                        "Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. 03/11/2026 10:15",
                        "Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. Ref A-1042. 03/11/2026 10:15 thanks",
                        "Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. Ref . 03/11/2026 10:15",
                        "Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. Ref A-1042 03/11/2026 10:15",
                        "Received 5000 XAF from Ana K. (+999 000 000 1234). Txn TX123456. ref A-1042. 03/11/2026 10:15"));
    }

    public static PaymentTemplates goodSet() {
        return PaymentTemplates.of(List.of(good(), goodRef()));
    }

    static PaymentTemplate compile(String template) {
        return PaymentTemplate.compile(bare(template));
    }
}
