package dev.chatledger.demo;

import java.io.Serializable;
import java.util.List;

/**
 * One scenario as it will be sent: the customer's order message and the confirmations that follow it, in order, plus the chat lines shown
 * around them. Plain text only; it is kept in the visitor's own server-side session between "Send the order" and "Send the payment".
 *
 * @param reference the order code the order and its confirmation share (null when there is none)
 * @param orderTotalMinor what the catalog makes the order cost
 */
public record Script(String customerName, String reference, long orderTotalMinor, String order, List<String> payments,
                     List<String> chatAfterOrder) implements Serializable {

    private static final long serialVersionUID = 1L;

    public Script {
        payments = List.copyOf(payments);
        chatAfterOrder = List.copyOf(chatAfterOrder);
        if (payments.isEmpty()) {
            throw new IllegalArgumentException("a scenario sends at least one confirmation");
        }
    }
}
