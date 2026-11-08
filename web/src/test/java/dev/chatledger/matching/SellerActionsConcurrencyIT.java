package dev.chatledger.matching;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.count;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seller's decisions racing each other with real threads against PostgreSQL as the application role: the same decision sixteen
 * times at once, opposite decisions on one suggestion, an unmatch against a payment arriving for the same order, and a storm of random
 * decisions and arrivals from sixteen threads. Nothing may throw, deadlock or leave the books inconsistent, and each decision is made at most once.
 * (Races that cannot be timed, such as a row changing between two statements of one transaction, are simulated elsewhere, by a seam
 * that acts on the transaction's own connection: {@code MatchingEdgeCasesIT}.)
 *
 * <p>Requirements: 3.7, 4.2, 4.3. Task 6.3.
 */
class SellerActionsConcurrencyIT {

    private static final int THREADS = 16;

    private final MatchingFixture f = new MatchingFixture();
    private final MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final UUID seller = MatchingFixture.user(tenant, "SELLER");
    private final UUID helper = MatchingFixture.user(tenant, "HELPER");

    /** Runs all the tasks at the same moment and returns what each returned; any exception, or a task still running after a minute, fails. */
    private static <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await(30, TimeUnit.SECONDS);
        go.countDown();
        List<T> out = new ArrayList<>();
        try {
            for (Future<T> future : futures) {
                out.add(future.get(90, TimeUnit.SECONDS));
            }
        } catch (ExecutionException e) {
            throw new AssertionError("a decision threw: " + e.getCause(), e.getCause());
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    private UUID order(String ref, String name, String phone, long total) {
        return quiet.orders.ingest(tenant, seller, id(), MatchingFixture.orderText(ref, name, phone, total)).orderId();
    }

    private UUID payment(UUID actor, String text) {
        return quiet.payments.ingest(tenant, actor, id(), text).paymentId();
    }

    @Test
    void sixteen_threads_unmatching_one_match_reverse_it_exactly_once() throws Exception {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = f.payments.ingest(tenant, seller, id(), f.sms(5000, "Ana Kamga", PHONE, "A-1042")).paymentId();
        UUID match = f.repository.forPayment(tenant, payment).get(0).id();
        List<Callable<MatchingService.Decision>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> f.matcher.unmatch(tenant, match, seller));
        }

        List<MatchingService.Decision> results = race(tasks);

        assertThat(results).filteredOn(MatchingService.Decision::done).hasSize(1);
        assertThat(results).filteredOn(d -> !d.done()).hasSize(THREADS - 1).allSatisfy(d -> assertThat(d.reason()).isEqualTo(MatchingService.Reason.NOT_ACTIVE));
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND kind = 'REVERSAL'", tenant, null)).isEqualTo(1);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("RECEIVED");
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void sixteen_threads_confirming_one_suggestion_make_exactly_one_match() throws Exception {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        UUID payment = f.payments.ingest(tenant, helper, id(), f.sms(5000, "Ana Kamga", PHONE, "A-1042")).paymentId();
        UUID suggestion = f.repository.forPayment(tenant, payment).get(0).id();
        List<Callable<MatchingService.ApplyResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> f.matcher.confirm(tenant, suggestion, seller));
        }

        List<MatchingService.ApplyResult> results = race(tasks);

        assertThat(results).filteredOn(r -> r.outcome() == MatchingService.Outcome.APPLIED).hasSize(1);
        assertThat(results).filteredOn(r -> r.outcome() != MatchingService.Outcome.APPLIED).hasSize(THREADS - 1)
                .allSatisfy(r -> assertThat(r.reason()).isEqualTo(MatchingService.Reason.PAYMENT_NOT_FREE));
        assertThat(count("SELECT count(*) FROM match WHERE tenant_id = ? AND state = 'CONFIRMED'", tenant, null)).isEqualTo(1);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void a_confirmation_and_a_rejection_of_one_suggestion_at_the_same_moment_have_one_winner_each_time() throws Exception {
        int confirmed = 0;
        int rejected = 0;
        for (int round = 0; round < 20; round++) {
            UUID order = order("R-" + round, "Ana Kamga", PHONE, 5000);
            UUID payment = f.payments.ingest(tenant, helper, id(), f.sms(5000, "Ana Kamga", PHONE, "R-" + round)).paymentId();
            UUID suggestion = f.repository.forPayment(tenant, payment).get(0).id();
            List<Callable<Object>> tasks = List.of(() -> f.matcher.confirm(tenant, suggestion, seller), () -> f.matcher.reject(tenant, suggestion, seller));

            List<Object> results = race(tasks);

            MatchingService.ApplyResult confirm = (MatchingService.ApplyResult) results.get(0);
            MatchingService.Decision reject = (MatchingService.Decision) results.get(1);
            boolean confirmWon = confirm.outcome() == MatchingService.Outcome.APPLIED;
            assertThat(confirmWon ^ reject.done()).as("exactly one of them wins in round " + round).isTrue();
            String state = f.repository.find(tenant, suggestion).orElseThrow().state();
            if (confirmWon) {
                confirmed++;
                assertThat(state).isEqualTo("CONFIRMED");
                assertThat(reject.reason()).isEqualTo(MatchingService.Reason.NOT_SUGGESTED);
                assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
            } else {
                rejected++;
                assertThat(state).isEqualTo("REJECTED");
                assertThat(confirm.reason()).isIn(MatchingService.Reason.NOT_SUGGESTED);
                assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
                assertThat(MatchingFixture.status("payment", payment)).isEqualTo("RECEIVED");
            }
        }
        assertThat(confirmed + rejected).isEqualTo(20);
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void an_unmatch_and_a_payment_matched_by_hand_to_the_same_order_at_the_same_moment_keep_the_books_right() throws Exception {
        for (int round = 0; round < 15; round++) {
            UUID order = order("U-" + round, "Ana Kamga", PHONE, 5000);
            UUID first = payment(seller, quiet.sms(3000, "Ana Kamga", PHONE, "U-" + round));
            UUID second = payment(seller, quiet.sms(3000, "Ana Kamga", PHONE, "U-" + round));
            UUID match = f.matcher.matchManually(tenant, order, first, seller).matchId();
            List<Callable<Object>> tasks = List.of(() -> f.matcher.unmatch(tenant, match, seller), () -> f.matcher.matchManually(tenant, order, second, seller));

            List<Object> results = race(tasks);

            assertThat(((MatchingService.Decision) results.get(0)).done()).as("round " + round).isTrue();
            assertThat(((MatchingService.ApplyResult) results.get(1)).outcome()).as("round " + round).isEqualTo(MatchingService.Outcome.APPLIED);
            assertThat(MatchingFixture.status("payment", first)).isEqualTo("RECEIVED");
            assertThat(MatchingFixture.status("payment", second)).isEqualTo("MATCHED");
            // unmatch first: the second payment applies its 3,000; payment first: it applies the 2,000 left and then the unmatch takes 3,000 away
            assertThat(f.repository.appliedMinor(tenant, order)).as("round " + round).isIn(2000L, 3000L);
        }
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void sixteen_threads_making_random_decisions_never_deadlock_throw_or_unbalance_the_books() throws Exception {
        List<UUID> orders = new ArrayList<>();
        List<UUID> payments = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            orders.add(order("S-" + i, "Ana Kamga", PHONE, 4000 + 1000L * i));
        }
        for (int i = 0; i < 8; i++) {
            UUID actor = i % 3 == 0 ? helper : seller;
            payments.add(payment(actor, quiet.sms(1000L + 1500L * i, "Ana Kamga", PHONE, "S-" + (i % 6))));
        }
        for (UUID payment : payments) {
            f.matcher.onPaymentRecorded(tenant, payment);
        }
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            tasks.add(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int done = 0;
                for (int i = 0; i < 40; i++) {
                    UUID anyPayment = payments.get(random.nextInt(payments.size()));
                    // (read through the application's pooled connections: a connection per read from sixteen threads runs the test machine out of ports)
                    List<MatchRepository.Stored> ofPayment = f.repository.forPayment(tenant, anyPayment);
                    UUID anyMatch = ofPayment.isEmpty() ? UUID.randomUUID() : ofPayment.get(random.nextInt(ofPayment.size())).id();
                    UUID anyOrder = orders.get(random.nextInt(orders.size()));
                    switch (random.nextInt(9)) {
                        case 0 -> done += f.matcher.confirm(tenant, anyMatch, seller).outcome() == MatchingService.Outcome.APPLIED ? 1 : 0;
                        case 1 -> done += f.matcher.reject(tenant, anyMatch, seller).done() ? 1 : 0;
                        case 2 -> done += f.matcher.matchManually(tenant, anyOrder, anyPayment, seller).outcome() == MatchingService.Outcome.APPLIED ? 1 : 0;
                        case 3 -> done += f.matcher.unmatch(tenant, anyMatch, seller).done() ? 1 : 0;
                        case 4 -> done += f.matcher.markUnmatched(tenant, anyPayment, seller).done() ? 1 : 0;
                        case 5 -> done += f.matcher.reopen(tenant, anyPayment, seller).done() ? 1 : 0;
                        case 6 -> done += f.matcher.rematch(tenant, seller).applied();
                        // new records arrive while the seller decides: the engine stores suggestions (key shares on the order and the payment)
                        case 7 -> f.payments.ingest(tenant, random.nextBoolean() ? helper : seller, id(), f.sms(1000L + 500L * random.nextInt(12), "Ana Kamga", PHONE, "S-" + random.nextInt(6)));
                        default -> f.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("S-" + random.nextInt(6), "Ana Kamga", PHONE, 4000L + 1000L * random.nextInt(6)));
                    }
                }
                return done;
            });
        }

        List<Integer> results = race(tasks);

        assertThat(results.stream().mapToInt(Integer::intValue).sum()).as("some decisions were actually made").isGreaterThan(THREADS);
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void sixteen_rematches_at_once_make_each_automatic_match_once() throws Exception {
        for (int i = 0; i < 6; i++) {
            order("T-" + i, "Ana Kamga", PHONE, 3000 + 100L * i);
            payment(seller, quiet.sms(3000 + 100L * i, "Ana Kamga", PHONE, "T-" + i));
        }
        List<Callable<MatchingService.RematchResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> f.matcher.rematch(tenant, seller));
        }

        List<MatchingService.RematchResult> results = race(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r.refusal()).isNull());
        assertThat(count("SELECT count(*) FROM match WHERE tenant_id = ? AND state = 'AUTO'", tenant, null)).isEqualTo(6);
        assertThat(results.stream().mapToInt(MatchingService.RematchResult::applied).sum()).as("applied once in total").isEqualTo(6);
        MatchInvariants.assertHold(tenant);
    }
}
