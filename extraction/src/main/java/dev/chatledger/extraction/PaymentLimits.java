package dev.chatledger.extraction;

import java.time.Duration;

/** Hard limits for payment messages and templates. Named constants: raising one is a deliberate decision. */
public final class PaymentLimits {

    /** A concatenated SMS is at most about 1,500 characters; 1,000 covers the usual confirmation. */
    public static final int MAX_SMS_CHARS = 1_000;
    public static final int MAX_NORMALIZED_CHARS = 2_000;
    public static final int MAX_TEMPLATES = 20;
    public static final int MAX_TEMPLATE_CHARS = 500;
    public static final int MAX_HOLES = 10;
    public static final int MIN_LITERAL_CHARS = 12;
    public static final int MIN_EXAMPLES = 3;
    public static final int MIN_COUNTER_EXAMPLES = 5;
    public static final int MAX_FILE_BYTES = 8 * 1024;
    public static final int MIN_TRANSACTION_ID = 6;
    public static final int MAX_TRANSACTION_ID = 40;
    public static final int MIN_NAME = 2;
    public static final int MAX_NAME = 40;
    /** A confirmation may be at most this far ahead of the server clock (clock skew). */
    public static final Duration MAX_FUTURE = Duration.ofMinutes(15);
    /** A confirmation older than this is not accepted as a payment (it is kept as unparsed). */
    public static final Duration MAX_AGE = Duration.ofDays(35);

    private PaymentLimits() {
    }
}
