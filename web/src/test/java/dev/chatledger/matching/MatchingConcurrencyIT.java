package dev.chatledger.matching;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.chatledger.matching.MatchingFixture.count;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sixteen threads at once. The locks (the payment first, then the order), the unique indexes and the commit-time triggers together must
 * leave exactly the matches a single thread could have made: a payment matched once, an order never over-applied, one posting per event,
 * a ledger that balances, and no deadlock. Each test runs its threads once per run; this is a check of the design, not a stress test.
 *
 * <p>Requirements: 3, 4.1. Task 6.2.
 */
class MatchingConcurrencyIT {

    private static final int THREADS = 16;

    private final MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);

    /** Runs the tasks together, after a common start, and returns every outcome; a task that threw is reported, not hidden. */
    private static <T> List<Object> together(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return (Object) task.call();
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        start.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }

    private static long applied(List<Object> results) {
        return results.stream().filter(r -> r instanceof MatchingService.ApplyResult a && a.outcome() == MatchingService.Outcome.APPLIED).count();
    }

    private static void noThrowable(List<Object> results) {
        assertThat(results).as("no thread failed or deadlocked").noneMatch(r -> r instanceof Throwable);
    }

    @Test
    void sixteen_threads_matching_one_payment_to_sixteen_orders_make_one_match_and_one_posting() throws Exception {
        MatchWorld w = new MatchWorld();
        UUID payment = w.payment(1000, "RECEIVED");
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            orders.add(w.order(1000, "PENDING_PAYMENT", "R-" + i, null));
        }
        List<Callable<MatchingService.ApplyResult>> tasks = new ArrayList<>();
        for (UUID order : orders) {
            tasks.add(() -> quiet.matcher.apply(w.tenant, order, payment, MatchingService.Mode.CONFIRM, w.user));
        }
        List<Object> results = together(tasks);

        noThrowable(results);
        assertThat(applied(results)).isEqualTo(1);
        assertThat(results.stream().filter(r -> r instanceof MatchingService.ApplyResult a && a.outcome() == MatchingService.Outcome.NOT_APPLICABLE))
                .hasSize(THREADS - 1).allSatisfy(r -> assertThat(((MatchingService.ApplyResult) r).reason()).isEqualTo(MatchingService.Reason.PAYMENT_NOT_FREE));
        assertThat(count("SELECT count(*) FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED')", w.tenant, null)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND kind = 'PAYMENT_MATCHED'", w.tenant, null)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND kind = 'ORDER_ACCEPTED'", w.tenant, null)).as("only the winner's order was posted").isEqualTo(1);
        assertThat(MatchingFixture.trialBalance(w.tenant)).isZero();
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("MATCHED");
    }

    @Test
    void sixteen_payments_into_one_order_never_apply_more_than_its_total() throws Exception {
        MatchWorld w = new MatchWorld();
        UUID order = w.order(1000, "PENDING_PAYMENT", "R-1", null);
        List<Callable<MatchingService.ApplyResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            UUID payment = w.payment(100, "RECEIVED");
            tasks.add(() -> quiet.matcher.apply(w.tenant, order, payment, MatchingService.Mode.CONFIRM, w.user));
        }
        List<Object> results = together(tasks);

        noThrowable(results);
        assertThat(applied(results)).as("ten payments of 100 fill an order of 1,000").isEqualTo(10);
        assertThat(count("SELECT COALESCE(SUM(amount_minor), 0) FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED')", w.tenant, null)).isEqualTo(1000);
        assertThat(count("SELECT COALESCE(SUM(excess_minor), 0) FROM match WHERE tenant_id = ?", w.tenant, null)).isZero();
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
        assertThat(MatchingFixture.balance(w.tenant, "1100")).isZero();
        assertThat(MatchingFixture.balance(w.tenant, "1000")).isEqualTo(1000);
        assertThat(MatchingFixture.trialBalance(w.tenant)).isZero();
        assertThat(count("SELECT count(*) FROM payment WHERE tenant_id = ? AND status = 'RECEIVED'", w.tenant, null)).isEqualTo(6);
    }

    @Test
    void sixteen_large_payments_into_one_order_apply_its_total_once_and_record_only_the_excess_of_the_payment_that_crosses_it() throws Exception {
        MatchWorld w = new MatchWorld();
        UUID order = w.order(1000, "PENDING_PAYMENT", "R-1", null);
        List<Callable<MatchingService.ApplyResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            UUID payment = w.payment(300, "RECEIVED");
            tasks.add(() -> quiet.matcher.apply(w.tenant, order, payment, MatchingService.Mode.CONFIRM, w.user));
        }
        List<Object> results = together(tasks);

        noThrowable(results);
        assertThat(applied(results)).as("three of 300 and one that crosses the total").isEqualTo(4);
        assertThat(count("SELECT COALESCE(SUM(amount_minor), 0) FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED')", w.tenant, null)).isEqualTo(1000);
        assertThat(count("SELECT COALESCE(SUM(excess_minor), 0) FROM match WHERE tenant_id = ?", w.tenant, null)).isEqualTo(200);
        assertThat(MatchingFixture.balance(w.tenant, "2100")).as("200 owed to the customer").isEqualTo(-200);
        assertThat(MatchingFixture.balance(w.tenant, "1100")).isZero();
        assertThat(MatchingFixture.balance(w.tenant, "1000")).isEqualTo(1200);
        assertThat(MatchingFixture.trialBalance(w.tenant)).isZero();
    }

    @Test
    void the_engine_run_twice_for_one_payment_at_the_same_time_makes_one_automatic_match() throws Exception {
        MatchWorld w = new MatchWorld();
        UUID seller = MatchingFixture.user(w.tenant, "SELLER");
        UUID order = quiet.orders.ingest(w.tenant, seller, MatchingFixture.id(), MatchingFixture.orderText("A-1042", "Ana Kamga", MatchingFixture.PHONE, 5000)).orderId();
        UUID payment = quiet.payments.ingest(w.tenant, seller, MatchingFixture.id(), quiet.sms(5000, "Ana Kamga", MatchingFixture.PHONE, "A-1042")).paymentId();
        List<Callable<MatchingService.Run>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> quiet.matcher.onPaymentRecorded(w.tenant, payment));
        }
        List<Object> results = together(tasks);

        noThrowable(results);
        assertThat(results.stream().filter(r -> ((MatchingService.Run) r).kind() == MatchingService.RunKind.APPLIED)).hasSize(1);
        assertThat(results.stream().filter(r -> ((MatchingService.Run) r).kind() == MatchingService.RunKind.SKIPPED)).hasSize(THREADS - 1);
        assertThat(count("SELECT count(*) FROM match WHERE tenant_id = ? AND state = 'AUTO'", w.tenant, null)).isEqualTo(1);
        assertThat(MatchingFixture.ledger(w.tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000",
                "PAYMENT_MATCHED 1000 D5000", "PAYMENT_MATCHED 1100 C5000");
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
    }

    @Test
    void sixteen_payments_arriving_at_once_for_sixteen_orders_are_each_matched_to_their_own() throws Exception {
        MatchWorld w = new MatchWorld();
        UUID seller = MatchingFixture.user(w.tenant, "SELLER");
        MatchingFixture live = new MatchingFixture();   // ingestion calls the matcher after each commit, as in production
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            orders.add(live.orders.ingest(w.tenant, seller, MatchingFixture.id(), MatchingFixture.orderText("R-" + i, "Ana Kamga", MatchingFixture.PHONE, 5000)).orderId());
        }
        List<Callable<UUID>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String text = live.sms(5000, "Ana Kamga", MatchingFixture.PHONE, "R-" + i);
            tasks.add(() -> live.payments.ingest(w.tenant, seller, MatchingFixture.id(), text).paymentId());
        }
        List<Object> results = together(tasks);

        noThrowable(results);
        assertThat(count("SELECT count(*) FROM match WHERE tenant_id = ? AND state = 'AUTO'", w.tenant, null)).isEqualTo(THREADS);
        assertThat(count("SELECT count(*) FROM orders WHERE tenant_id = ? AND status = 'PAID'", w.tenant, null)).isEqualTo(THREADS);
        assertThat(count("SELECT count(*) FROM payment WHERE tenant_id = ? AND status = 'MATCHED'", w.tenant, null)).isEqualTo(THREADS);
        assertThat(count("SELECT count(*) FROM (SELECT order_id FROM match WHERE tenant_id = ? AND state = 'AUTO' GROUP BY order_id HAVING count(*) > 1) x", w.tenant, null))
                .as("no order took two payments").isZero();
        assertThat(MatchingFixture.balance(w.tenant, "1100")).isZero();
        assertThat(MatchingFixture.balance(w.tenant, "1000")).isEqualTo(16 * 5000L);
        assertThat(MatchingFixture.trialBalance(w.tenant)).isZero();
        assertThat(orders).hasSize(THREADS);
    }

    @Test
    void random_pairs_from_many_threads_in_every_order_never_deadlock_and_keep_every_invariant() throws Exception {
        MatchWorld w = new MatchWorld();
        List<UUID> orders = new ArrayList<>();
        List<UUID> payments = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            orders.add(w.order(1500, "PENDING_PAYMENT", "R-" + i, null));
            payments.add(w.payment(1000, "RECEIVED"));
        }
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            long seed = t * 7919L + 13;
            tasks.add(() -> {
                Random random = new Random(seed);
                int applied = 0;
                for (int i = 0; i < 12; i++) {
                    MatchingService.ApplyResult r = quiet.matcher.apply(w.tenant, orders.get(random.nextInt(4)), payments.get(random.nextInt(4)),
                            MatchingService.Mode.CONFIRM, w.user);
                    applied += r.outcome() == MatchingService.Outcome.APPLIED ? 1 : 0;
                }
                return applied;
            });
        }
        List<Object> results = together(tasks);

        noThrowable(results);
        long active = count("SELECT count(*) FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED')", w.tenant, null);
        assertThat(results.stream().mapToInt(r -> (Integer) r).sum()).isEqualTo((int) active);
        assertThat(count("SELECT count(*) FROM (SELECT payment_id FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED') GROUP BY payment_id HAVING count(*) > 1) x",
                w.tenant, null)).as("no payment matched twice").isZero();
        assertThat(count("SELECT count(*) FROM (SELECT o.id FROM orders o JOIN match m ON m.order_id = o.id AND m.state IN ('AUTO', 'CONFIRMED') "
                + "WHERE o.tenant_id = ? GROUP BY o.id, o.total_minor HAVING SUM(m.amount_minor) > o.total_minor) x", w.tenant, null)).as("no order over-applied").isZero();
        assertThat(count("SELECT count(*) FROM payment WHERE tenant_id = ? AND status = 'MATCHED'", w.tenant, null)).isEqualTo(active);
        assertThat(MatchingFixture.trialBalance(w.tenant)).isZero();
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND kind = 'PAYMENT_MATCHED'", w.tenant, null)).isEqualTo(active);
    }
}
