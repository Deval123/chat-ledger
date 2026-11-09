package dev.chatledger.suspicion;

import java.time.Instant;
import java.util.Objects;

/** An earlier payment from the same sender phone: the name it gave (may be null) and when it was paid. */
public record SenderRecord(String name, Instant paidAt) {
    public SenderRecord {
        Objects.requireNonNull(paidAt, "paidAt");
    }

    @Override
    public String toString() {
        return "SenderRecord[" + paidAt + "]";
    }
}
