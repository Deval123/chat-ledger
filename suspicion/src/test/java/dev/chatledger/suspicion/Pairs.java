package dev.chatledger.suspicion;

import dev.chatledger.matching.OrderFacts;
import dev.chatledger.matching.PaymentFacts;

import java.time.Instant;
import java.util.UUID;

/** Synthetic orders and payments for the pair rules. Names and numbers are invented. */
public final class Pairs {
    public static final Instant ORDERED = Instant.parse("2026-11-03T12:00:00Z");
    public static final String HASH_A = "aa".repeat(32);
    public static final String HASH_B = "bb".repeat(32);

    private Pairs() {
    }

    public static OrderFacts order(long total, String name, String hash, String last4) {
        return new OrderFacts(UUID.randomUUID(), total, 0, "XAF", ORDERED, null, name, hash, last4, false, false);
    }

    public static OrderFacts order(long total, long applied) {
        return new OrderFacts(UUID.randomUUID(), total, applied, "XAF", ORDERED, null, null, null, null, false, false);
    }

    public static PaymentFacts payment(long amount, String name, String hash, String last4, Instant paidAt) {
        return new PaymentFacts(UUID.randomUUID(), amount, "XAF", true, null, name, hash, last4, paidAt, false);
    }

    public static PaymentFacts payment(long amount) {
        return payment(amount, null, null, null, ORDERED.plusSeconds(60));
    }

    public static PairContext pair(OrderFacts o, PaymentFacts p) {
        return new PairContext(o, p);
    }
}
