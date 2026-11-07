package dev.chatledger.extraction;

import java.util.List;

/**
 * Structured order data read from a chat message. It holds only what the parser read; nothing here is computed
 * from the catalog.
 *
 * <p>All money amounts are integers in minor units. {@code toString} prints counts and flags only: a name, a
 * phone number, an item label or the text itself can never reach a log through this record.
 *
 * @param reference    The order code written in the message (may be null).
 * @param customerName Customer name as written (may be null).
 * @param phone        The customer's phone in canonical form (may be null); masked when printed.
 * @param lines        The item lines (at least one when produced by the parser).
 * @param totalMinor   The total as stated in the message, or null when the message has none.
 * @param currency     The currency the message states, normalised to an ISO code ({@code FCFA} becomes
 *                     {@code XAF}), or null when it states none.
 * @param deliveryNote The delivery note as written (may be null).
 * @param rawText      The original text, exactly as received.
 */
public record ParsedOrder(
        String reference,
        String customerName,
        Phone phone,
        List<OrderLine> lines,
        Long totalMinor,
        String currency,
        String deliveryNote,
        String rawText
) implements ParsedMessage {

    public ParsedOrder {
        lines = List.copyOf(lines);
    }

    /**
     * One item line. At most one of {@code unitMinor} (written "@ price") and {@code lineTotalMinor} (written
     * "= amount") is set; both null means the message gave no price for it.
     */
    public record OrderLine(String label, int qty, Long unitMinor, Long lineTotalMinor) {
        @Override
        public String toString() {
            return "OrderLine[qty=" + qty + "]";
        }
    }

    @Override
    public String toString() {
        return "ParsedOrder[lines=" + lines.size() + ", hasTotal=" + (totalMinor != null)
                + ", hasPhone=" + (phone != null) + ", hasName=" + (customerName != null) + "]";
    }
}
