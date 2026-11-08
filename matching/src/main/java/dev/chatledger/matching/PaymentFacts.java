package dev.chatledger.matching;

import dev.chatledger.extraction.OrderLimits;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** What the scorer may know about a payment that is free to be matched. Parsed and validated fields only. */
public record PaymentFacts(UUID id, long amountMinor, String currency, boolean hasTransactionId, String reference, String senderName,
                           String senderPhoneHash, String senderLast4, Instant paidAt) {

    public PaymentFacts {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        if (amountMinor < 1 || amountMinor > OrderLimits.MAX_MINOR) {
            throw new IllegalArgumentException("amount out of range");
        }
    }

    @Override
    public String toString() {
        return "PaymentFacts[" + id + "]";
    }
}
