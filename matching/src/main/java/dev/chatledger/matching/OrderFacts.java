package dev.chatledger.matching;

import dev.chatledger.extraction.OrderLimits;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What the scorer may know about an open order. Parsed and validated fields only: there is no raw text here, so scoring cannot read it.
 *
 * @param appliedMinor    The sum of the order's active matches (the balance is {@code totalMinor - appliedMinor}).
 * @param referenceShared True if another open order of the tenant has the same reference key; then a matching reference proves nothing.
 * @param flagged         True if the order has a flag of its own (open or resolved). Such an order is never matched automatically.
 */
public record OrderFacts(UUID id, long totalMinor, long appliedMinor, String currency, Instant createdAt, String orderRef,
                         String customerName, String customerPhoneHash, String customerLast4, boolean referenceShared,
                         boolean flagged) {

    public OrderFacts {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(createdAt, "createdAt");
        if (totalMinor < 1 || totalMinor > OrderLimits.MAX_MINOR) {
            throw new IllegalArgumentException("total out of range");
        }
        if (appliedMinor < 0 || appliedMinor > totalMinor) {
            throw new IllegalArgumentException("applied amount out of range");
        }
    }

    public long balanceMinor() {
        return totalMinor - appliedMinor;
    }

    @Override
    public String toString() {
        return "OrderFacts[" + id + "]";
    }
}
