package dev.chatledger.matching;

import dev.chatledger.orders.IngestionResult;
import dev.chatledger.orders.RecordActionsService;
import dev.chatledger.orders.RecordActionsService.Outcome;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cancel and a match of the same order, at the same moment, in both orders of arrival (task 12b, the owner's condition 2). The first
 * transaction stops right after it holds the order's lock; the second is released only once the database shows it waiting for that lock,
 * so the order of arrival is the one the test names, not a chance of the scheduler. The one rule: exactly one of them wins, the other is
 * refused with its reason, and the books hold. A cancel takes no payment lock, so the two cannot deadlock (the match locks the payment,
 * then the order; the cancel only the order).
 */
class CancelMatchConcurrencyIT {

    private static final long TIMEOUT_S = 60;

    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final UUID seller = MatchingFixture.user(tenant, "SELLER");

    /** A gate: the first transaction to pass {@code arm}ed code stops in it until {@link #release()}. */
    static final class Gate {
        final AtomicBoolean armed = new AtomicBoolean(true);
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch go = new CountDownLatch(1);

        void stop() {
            if (armed.compareAndSet(true, false)) {
                held.countDown();
                try {
                    if (!go.await(TIMEOUT_S, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the gate was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }

        void awaitHeld() throws InterruptedException {
            assertThat(held.await(TIMEOUT_S, TimeUnit.SECONDS)).as("the first transaction took the order lock").isTrue();
        }

        void release() {
            go.countDown();
        }
    }

    private UUID payableOrder(String ref) {
        MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
        IngestionResult r = quiet.orders.ingest(tenant, seller, id(), MatchingFixture.orderText(ref, "Ana Kamga", PHONE, 5000));
        assertThat(r.outcome()).isEqualTo(IngestionResult.Outcome.CREATED);
        return r.orderId();
    }

    /** Waits until a session of this database waits for a row lock taken by lockOrder (the statement both actions run). */
    private static void awaitWaitingOnTheOrder() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S);
        try (Connection c = MatchWorld.DB.superDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                     + "AND wait_event_type = 'Lock' AND query LIKE '%FOR NO KEY UPDATE OF o%'")) {
            while (System.nanoTime() < end) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        throw new AssertionError("the second transaction never waited for the order lock");
    }

    @Test
    void the_match_holds_the_order_first_so_it_applies_and_the_cancel_is_refused() throws Exception {
        UUID order = payableOrder("CC-1");
        UUID payment = w.payment(5000, "RECEIVED");
        Gate gate = new Gate();
        MatchingFixture gated = new MatchingFixture(MatchConfig.defaults(), (step, c) -> {
            if (step == MatchingService.ApplyStep.LOCKED) {
                gate.stop();
            }
        }, false);
        RecordActionsService actions = new RecordActionsService(MatchingFixture.POOL, gated.repository, gated.orderRepository, gated.clock);

        CompletableFuture<MatchingService.ApplyResult> match = CompletableFuture.supplyAsync(() -> gated.matcher.matchManually(tenant, order, payment, seller));
        gate.awaitHeld();
        CompletableFuture<Outcome> cancel = CompletableFuture.supplyAsync(() -> actions.cancel(tenant, order, seller));
        awaitWaitingOnTheOrder();
        gate.release();

        assertThat(match.get(TIMEOUT_S, TimeUnit.SECONDS).outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        assertThat(cancel.get(TIMEOUT_S, TimeUnit.SECONDS)).isEqualTo(Outcome.HAS_PAYMENT);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("MATCHED");
        MatchInvariants.assertHoldStrict(tenant);
    }

    @Test
    void the_cancel_holds_the_order_first_so_it_cancels_and_the_match_is_refused() throws Exception {
        UUID order = payableOrder("CC-2");
        UUID payment = w.payment(5000, "RECEIVED");
        Gate gate = new Gate();
        MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
        RecordActionsService actions = new RecordActionsService(MatchingFixture.POOL, quiet.repository, quiet.orderRepository, quiet.clock,
                (step, c) -> {
                    if (step == RecordActionsService.Step.LOCKED) {
                        gate.stop();
                    }
                });

        CompletableFuture<Outcome> cancel = CompletableFuture.supplyAsync(() -> actions.cancel(tenant, order, seller));
        gate.awaitHeld();
        CompletableFuture<MatchingService.ApplyResult> match = CompletableFuture.supplyAsync(() -> quiet.matcher.matchManually(tenant, order, payment, seller));
        awaitWaitingOnTheOrder();
        gate.release();

        assertThat(cancel.get(TIMEOUT_S, TimeUnit.SECONDS)).isEqualTo(Outcome.DONE);
        MatchingService.ApplyResult refused = match.get(TIMEOUT_S, TimeUnit.SECONDS);
        assertThat(refused.outcome()).isEqualTo(MatchingService.Outcome.NOT_APPLICABLE);
        assertThat(refused.reason()).isEqualTo(MatchingService.Reason.ORDER_NOT_OPEN);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("CANCELLED");
        assertThat(MatchingFixture.status("payment", payment)).as("the payment is free to match another order").isEqualTo("RECEIVED");
        MatchInvariants.assertHoldStrict(tenant);
    }

    /** Without a gate, the engine against a cancel, many times: whichever wins, there is exactly one winner and the books hold. */
    @Test
    void a_cancel_racing_the_engine_has_exactly_one_winner_every_time() throws Exception {
        MatchingFixture live = new MatchingFixture();
        RecordActionsService actions = new RecordActionsService(MatchingFixture.POOL, live.repository, live.orderRepository, live.clock);
        int cancelled = 0;
        int matched = 0;
        for (int round = 0; round < 20; round++) {
            String ref = "CR-" + round;
            UUID order = payableOrder(ref);
            CountDownLatch go = new CountDownLatch(1);
            CompletableFuture<Outcome> cancel = CompletableFuture.supplyAsync(() -> {
                await(go);
                return actions.cancel(tenant, order, seller);
            });
            CompletableFuture<UUID> payment = CompletableFuture.supplyAsync(() -> {
                await(go);
                return live.payments.ingest(tenant, seller, id(), live.sms(5000, "Ana Kamga", PHONE, ref)).paymentId();
            });
            go.countDown();
            Outcome outcome = cancel.get(TIMEOUT_S, TimeUnit.SECONDS);
            UUID paid = payment.get(TIMEOUT_S, TimeUnit.SECONDS);
            String orderStatus = MatchingFixture.status("orders", order);
            if (outcome == Outcome.DONE) {
                cancelled++;
                assertThat(orderStatus).isEqualTo("CANCELLED");
                assertThat(MatchingFixture.status("payment", paid)).isEqualTo("RECEIVED");
            } else {
                matched++;
                assertThat(outcome).isEqualTo(Outcome.HAS_PAYMENT);
                assertThat(orderStatus).isEqualTo("PAID");
            }
        }
        assertThat(cancelled + matched).isEqualTo(20);
        MatchInvariants.assertHoldStrict(tenant);
    }

    private static void await(CountDownLatch go) {
        try {
            go.await(TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
