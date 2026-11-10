package dev.chatledger.demo.web;

import dev.chatledger.board.Notice;
import dev.chatledger.demo.DemoMessages;
import dev.chatledger.demo.Scenario;
import dev.chatledger.demo.Script;
import dev.chatledger.orders.IngestionResult;
import dev.chatledger.orders.OrderIngestionService;
import dev.chatledger.payments.PaymentIngestionResult;
import dev.chatledger.payments.PaymentIngestionService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs one step of a one-click scenario (requirement 7.2) through the same ingestion services as a pasted message, as the signed-in
 * demo seller, with external ids {@code sim:<uuid>}. There is no other way in: no SQL, no text from the request. The script made at "Send
 * the order" is kept in the caller's store (the visitor's own session) until "Send the payment" sends its confirmations.
 */
public class DemoScenarios {

    public enum Step {
        ORDER, PAYMENT, BOTH;

        public static Optional<Step> byPath(String path) {
            for (Step s : values()) {
                if (s.name().toLowerCase(java.util.Locale.ROOT).equals(path)) {
                    return Optional.of(s);
                }
            }
            return Optional.empty();
        }
    }

    /** What happened: the sentence, whether to show the board (after a payment) or stay on the scenarios page, and a wait if limited. */
    public record Outcome(Notice notice, boolean toBoard, long retryAfterSeconds) {
        public boolean rateLimited() {
            return retryAfterSeconds > 0;
        }
    }

    private final OrderIngestionService orders;
    private final PaymentIngestionService payments;
    private final DemoMessages messages;

    public DemoScenarios(OrderIngestionService orders, PaymentIngestionService payments, DemoMessages messages) {
        this.orders = orders;
        this.payments = payments;
        this.messages = messages;
    }

    public DemoMessages messages() {
        return messages;
    }

    /** @param store the visitor's scripts by scenario; written here */
    public Outcome run(UUID tenantId, UUID actor, Scenario scenario, Step step, Map<Scenario, Script> store) {
        Script script;
        if (step == Step.PAYMENT) {
            script = store.get(scenario);
            if (script == null) {
                return new Outcome(Notice.DEMO_SEND_ORDER_FIRST, false, 0);
            }
        } else {
            script = scenario.script(messages);
            IngestionResult order = orders.ingest(tenantId, actor, externalId(), script.order());
            if (order.outcome() == IngestionResult.Outcome.RATE_LIMITED) {
                return new Outcome(null, false, Math.max(1, order.retryAfterSeconds()));
            }
            if (order.outcome() == IngestionResult.Outcome.REJECTED_INPUT) {
                return new Outcome(Notice.rejected(order.rejectReason(), Notice.ORDER_INPUT_REJECTED), false, 0);
            }
            if (step == Step.ORDER) {
                store.put(scenario, script);
                return new Outcome(Notice.DEMO_ORDER_SENT, false, 0);
            }
        }
        store.remove(scenario);
        for (String text : script.payments()) {
            PaymentIngestionResult payment = payments.ingest(tenantId, actor, externalId(), text);
            if (payment.outcome() == PaymentIngestionResult.Outcome.RATE_LIMITED) {
                return new Outcome(null, true, Math.max(1, payment.retryAfterSeconds()));
            }
            if (payment.outcome() == PaymentIngestionResult.Outcome.REJECTED_INPUT) {
                return new Outcome(Notice.rejected(payment.rejectReason(), Notice.PAYMENT_INPUT_REJECTED), true, 0);
            }
        }
        return new Outcome(Notice.DEMO_PAYMENT_SENT, true, 0);
    }

    private static String externalId() {
        return "sim:" + UUID.randomUUID();
    }
}
