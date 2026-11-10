package dev.chatledger.demo.ops;

import dev.chatledger.board.BoardRepository;
import dev.chatledger.demo.DemoMessages;
import dev.chatledger.demo.DemoSeed;
import dev.chatledger.matching.MatchingService;
import dev.chatledger.orders.IngestionResult;
import dev.chatledger.orders.OrderIngestionService;
import dev.chatledger.payments.PaymentIngestionResult;
import dev.chatledger.payments.PaymentIngestionService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes a demo workspace's seed ({@link DemoSeed}) through the same ingestion services as real input, as the workspace's own seller (a
 * trusted recorder, so the automatic case can be automatic), and confirms the part payment through the seller's own decision. There is no
 * SQL insert into orders or payments here. External ids are {@code seed:<tenant>:<step>}: a seed run again replays and adds nothing.
 */
public class DemoSeeder {

    private final OrderIngestionService orders;
    private final PaymentIngestionService payments;
    private final MatchingService matching;
    private final BoardRepository board;
    private final DemoMessages messages;

    public DemoSeeder(OrderIngestionService orders, PaymentIngestionService payments, MatchingService matching, BoardRepository board,
                      DemoMessages messages) {
        this.orders = orders;
        this.payments = payments;
        this.matching = matching;
        this.board = board;
        this.messages = messages;
    }

    /** Seeds one workspace; throws if any message is refused (the workspace then stays in SEEDING and is retired later). */
    public void seed(UUID tenantId, UUID sellerId) {
        Map<String, UUID> ids = new HashMap<>();
        for (DemoSeed.Step step : DemoSeed.plan(messages)) {
            String externalId = "seed:" + tenantId + ":" + step.key();
            switch (step) {
                case DemoSeed.OrderStep order -> {
                    IngestionResult r = orders.ingest(tenantId, sellerId, externalId, order.text());
                    if (r.orderId() == null) {
                        throw new IllegalStateException("the seed's order " + step.key() + " was refused: " + r.outcome());
                    }
                    ids.put(step.key(), r.orderId());
                }
                case DemoSeed.PaymentStep payment -> {
                    PaymentIngestionResult r = payments.ingest(tenantId, sellerId, externalId, payment.text());
                    if (r.paymentId() == null) {
                        throw new IllegalStateException("the seed's payment " + step.key() + " was refused: " + r.outcome());
                    }
                    ids.put(step.key(), r.paymentId());
                }
                case DemoSeed.ConfirmStep confirm -> suggestion(tenantId, ids.get(confirm.orderKey()), ids.get(confirm.paymentKey()))
                        .ifPresent(match -> matching.confirm(tenantId, match, sellerId));
            }
        }
    }

    /** The waiting suggestion between this order and this payment, if any (a seed run again finds it already confirmed). */
    private Optional<UUID> suggestion(UUID tenantId, UUID orderId, UUID paymentId) {
        if (orderId == null || paymentId == null) {
            return Optional.empty();
        }
        return board.payment(tenantId, paymentId, false).stream()
                .flatMap(p -> p.matches().stream())
                .filter(m -> "SUGGESTED".equals(m.state()) && orderId.equals(m.otherId()))
                .map(BoardRepository.MatchView::id)
                .findFirst();
    }
}
