package dev.chatledger.extraction;

/**
 * Why an extraction or a validation said no. A fixed set of codes, never text: a reason can be logged, stored
 * and shown without any risk that it carries something the sender wrote.
 */
public enum FailureReason {
    EMPTY_INPUT,
    INPUT_TOO_LARGE,
    TOO_MANY_LINES,
    TOO_MANY_ITEMS,
    NO_ITEMS,
    UNRECOGNIZED_LINE,
    DUPLICATE_FIELD,
    INVALID_QUANTITY,
    INVALID_AMOUNT,
    AMOUNT_TOO_LARGE,
    LINE_TOTAL_NOT_DIVISIBLE,
    INVALID_PHONE,
    INVALID_FIELD,
    FIELD_TOO_LONG,
    CURRENCY_MISMATCH,
    /** A money amount the parser reports is not written in the message. */
    AMOUNT_NOT_IN_SOURCE,
    /** The stated total differs from the sum of the lines: the seller must look. */
    TOTAL_MISMATCH,
    /** No total in the message and at least one item has no price in the message or the catalog. */
    PRICE_UNKNOWN,
    /** No payment template matches the whole message. */
    NO_TEMPLATE_MATCH,
    /** More than one template matches and they do not give the same result. */
    AMBIGUOUS_TEMPLATE,
    INVALID_DATE,
    /** The timestamp is valid but too far in the future or the past. */
    DATE_IMPLAUSIBLE,
    INVALID_TRANSACTION_ID,
    INVALID_REFERENCE,
    INVALID_NAME
}
