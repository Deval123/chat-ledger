package dev.chatledger.suspicion;

import java.util.Objects;
import java.util.UUID;

/**
 * What the order rule may know about an order that was just stored, in the transaction that stores it.
 *
 * @param parsedName the customer name written in this order (null if none)
 * @param storedName the stored name of the customer the order was attached to by its phone (null if the order has no phone). A customer
 *                   created by this order is stored with this order's name, so it always agrees: only an existing customer can differ.
 */
public record OrderContext(UUID orderId, String parsedName, String storedName) {
    public OrderContext {
        Objects.requireNonNull(orderId, "orderId");
    }

    @Override
    public String toString() {
        return "OrderContext[" + orderId + "]";
    }
}
