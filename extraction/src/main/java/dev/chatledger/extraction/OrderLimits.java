package dev.chatledger.extraction;

/**
 * Hard limits on what an order message may contain. Named constants, not scattered numbers: raising one is a
 * deliberate decision (it is listed as a weakened defense in the task 4 design).
 */
public final class OrderLimits {

    public static final int MAX_INPUT_CHARS = 4_000;
    /** Unicode normalisation can make text longer (compatibility characters expand); this bounds the result. */
    public static final int MAX_NORMALIZED_CHARS = 8_000;
    public static final int MAX_LINES = 80;
    public static final int MAX_ITEMS = 50;
    public static final int MIN_QTY = 1;
    public static final int MAX_QTY = 9_999;
    /** 10^12 minor units: far above any real order, small enough that sums of 50 lines cannot overflow a long. */
    public static final long MAX_MINOR = 1_000_000_000_000L;
    public static final int MAX_AMOUNT_DIGITS = 13;
    public static final int MAX_LABEL = 100;
    public static final int MAX_NAME = 100;
    public static final int MAX_NOTE = 300;
    public static final int MAX_REF = 64;

    private OrderLimits() {
    }
}
