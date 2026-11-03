package dev.chatledger.extraction;

import java.util.List;

/**
 * Structured order data extracted from a WhatsApp chat message.
 *
 * <p>All monetary amounts are integers in minor currency units (no floating point).
 * Phone numbers are never stored raw; the caller masks them before persistence.
 *
 * @param customerName  Customer name as found in the text (may be null).
 * @param phoneFragment Last 4 digits of the phone number, if present.
 * @param lines         Individual order lines (item, quantity, unit price).
 * @param totalMinor    Total in minor units, or {@code null} when not found in
 *                      the message (triggers catalog lookup or NEEDS_REVIEW).
 * @param currency      ISO currency code (e.g. "XAF").
 * @param deliveryNote  Optional delivery note extracted from the message.
 * @param rawText       Original message text, preserved for audit purposes.
 */
public record ParsedOrder(
        String customerName,
        String phoneFragment,
        List<OrderLine> lines,
        Long totalMinor,
        String currency,
        String deliveryNote,
        String rawText
) implements ParsedMessage {

    public ParsedOrder {
        lines = List.copyOf(lines);
    }

    /** A single line extracted from the order message. */
    public record OrderLine(String label, int qty, Long unitMinor) {}
}
