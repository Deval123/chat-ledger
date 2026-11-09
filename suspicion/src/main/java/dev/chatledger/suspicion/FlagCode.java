package dev.chatledger.suspicion;

/**
 * The ten suspicion codes. Each has one rule (R10 has one class for an order and one for a payment) and one fixed English sentence, which
 * is what is stored in {@code flag.reason}. The page shows the same sentence from {@code messages.properties} ({@code flag.CODE}); a test
 * keeps the two equal. No sentence contains anything from a message.
 *
 * <p>Requirement: 5. Task 7.
 */
public enum FlagCode {
    /** R1. */
    DUPLICATE_TX("This transaction id was already recorded. The same confirmation may have been sent twice."),
    /** R2. */
    NO_PROVIDER_ID("This confirmation has no transaction id. Check your provider balance before you hand over the goods."),
    /** R3. */
    AMOUNT_NEAR_MISS("The amount looks like the order total with a digit missing or swapped."),
    /** R5. */
    PAID_BEFORE_ORDER("This payment was made before the order existed."),
    /** R6. */
    SENDER_BURST("Many payments came from this sender in a short time."),
    /** R7. */
    PHONE_MISMATCH("The payment came from a different phone number than the one on the order."),
    /** R8. */
    NAME_MISMATCH("The sender's name does not resemble the customer's name."),
    /** R9. */
    PAID_OUTSIDE_USUAL_WINDOW("The payment time is far from when it was recorded."),
    /** R10, on an order or on a payment. */
    SENDER_ALIAS("This phone number was used before under a different name."),
    /** R11. */
    OVERPAYMENT("The payment is more than the order. A common trick is to overpay on purpose and ask for the difference back. "
            + "Check your balance before you send anything.");

    private final String sentence;

    FlagCode(String sentence) {
        this.sentence = sentence;
    }

    /** The fixed plain-language reason. */
    public String sentence() {
        return sentence;
    }
}
