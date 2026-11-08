package dev.chatledger.matching;

import dev.chatledger.orders.IngestionResult;
import dev.chatledger.orders.OrderApprovalService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.count;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seller states the total of an order that needs review and it becomes payable: one transaction that sets the total, posts
 * ORDER_ACCEPTED, audits who, and commits only if the books agree. Everything it refuses, and that a failure at any step leaves nothing.
 *
 * <p>Requirements: 1.2, 4.1, 6, 10. Task 8.
 */
class OrderApprovalIT {

    private final MatchingFixture f = new MatchingFixture();
    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final UUID seller = MatchingFixture.user(tenant, "SELLER");
    private final UUID helper = MatchingFixture.user(tenant, "HELPER");
    private final OrderApprovalService approvals = new OrderApprovalService(MatchingFixture.POOL, f.repository, f.orderRepository, f.matcher, f.clock);

    private UUID orderNeedingReview() {
        IngestionResult r = f.orders.ingest(tenant, seller, id(), "Order: A-1042\nName: Ana Kamga\nPhone: " + PHONE + "\n2 x rice\n1 x mystery");
        assertThat(r.outcome()).isEqualTo(IngestionResult.Outcome.CREATED_NEEDS_REVIEW);
        return r.orderId();
    }

    private String fingerprint() {
        return MatchInvariants.fingerprint(tenant);
    }

    @Test
    void approving_makes_the_order_payable_posts_it_once_and_audits_who() {
        UUID order = orderNeedingReview();
        assertThat(MatchingFixture.ledger(tenant)).isEmpty();

        OrderApprovalService.Outcome outcome = approvals.approve(tenant, order, seller, 5000);

        assertThat(outcome).isEqualTo(OrderApprovalService.Outcome.APPROVED);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
        assertThat(MatchingFixture.rows("SELECT total_minor::text FROM orders WHERE tenant_id = ? AND status = 'PENDING_PAYMENT'", tenant)).containsExactly("5000");
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000");
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'ORDER_APPROVED' AND actor = ?", tenant, seller)).isEqualTo(1);
        assertThat(MatchingFixture.rows("SELECT subject FROM audit_log WHERE tenant_id = ? AND action = 'ORDER_APPROVED'", tenant)).containsExactly(order.toString());
        assertThat(MatchingFixture.trialBalance(tenant)).isZero();
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void a_payment_that_was_already_waiting_is_matched_as_soon_as_the_order_is_payable() {
        UUID order = orderNeedingReview();
        UUID payment = f.payments.ingest(tenant, seller, id(), f.sms(5000, "Ana Kamga", PHONE, "A-1042")).paymentId();
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("RECEIVED");

        assertThat(approvals.approve(tenant, order, seller, 5000)).isEqualTo(OrderApprovalService.Outcome.APPROVED);

        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PAID");
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("MATCHED");
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000",
                "PAYMENT_MATCHED 1000 D5000", "PAYMENT_MATCHED 1100 C5000");
        MatchInvariants.assertHold(tenant);
    }

    @Test
    void a_failing_matcher_after_the_commit_does_not_undo_the_approval() {
        UUID order = orderNeedingReview();
        MatchTrigger failing = new MatchTrigger() {
            @Override
            public void paymentStored(UUID t, UUID id) {
            }

            @Override
            public void orderStored(UUID t, UUID id) {
                throw new IllegalStateException("the matcher is down");
            }
        };
        OrderApprovalService service = new OrderApprovalService(MatchingFixture.POOL, f.repository, f.orderRepository, failing, f.clock);
        assertThat(service.approve(tenant, order, seller, 5000)).isEqualTo(OrderApprovalService.Outcome.APPROVED);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
        assertThat(MatchingFixture.ledger(tenant)).hasSize(2);
    }

    @Test
    void refuses_a_user_who_is_not_an_enabled_seller_of_the_tenant_and_changes_nothing() {
        UUID order = orderNeedingReview();
        MatchWorld other = new MatchWorld();
        UUID sellerOfOther = MatchingFixture.user(other.tenant, "SELLER");
        UUID disabled = MatchingFixture.user(tenant, "SELLER");
        w.run("UPDATE app_user SET disabled_at = now() WHERE id = ?", disabled);
        String before = fingerprint();
        for (UUID who : new UUID[]{helper, disabled, sellerOfOther, UUID.randomUUID()}) {
            assertThat(approvals.approve(tenant, order, who, 5000)).isEqualTo(OrderApprovalService.Outcome.NOT_A_SELLER);
        }
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void refuses_an_unknown_order_and_another_tenants_order() {
        UUID order = orderNeedingReview();
        MatchWorld other = new MatchWorld();
        UUID sellerOfOther = MatchingFixture.user(other.tenant, "SELLER");
        assertThat(approvals.approve(tenant, UUID.randomUUID(), seller, 5000)).isEqualTo(OrderApprovalService.Outcome.NOT_FOUND);
        assertThat(approvals.approve(other.tenant, order, sellerOfOther, 5000)).isEqualTo(OrderApprovalService.Outcome.NOT_FOUND);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void refuses_an_order_that_is_not_waiting_for_review_whatever_its_status() {
        UUID payable = f.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("B-1", "Bea Nkomo", "+999 000 000 5678", 4000)).orderId();
        UUID unparsed = f.orders.ingest(tenant, seller, id(), "Hello, could you send two boxes of tomatoes tomorrow please?").orderId();
        UUID paid = w.order(5000, "PAID", null, null);
        UUID cancelled = w.order(5000, "PENDING_PAYMENT", null, null);
        MatchWorld.forced(c -> MatchWorld.run(c, "UPDATE orders SET status = 'CANCELLED' WHERE id = ?", cancelled));
        String before = fingerprint();
        for (UUID order : new UUID[]{payable, unparsed, paid, cancelled}) {
            assertThat(approvals.approve(tenant, order, seller, 9999)).as(MatchingFixture.status("orders", order)).isEqualTo(OrderApprovalService.Outcome.NOT_REVIEWABLE);
        }
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(MatchingFixture.rows("SELECT total_minor::text FROM orders WHERE id = '" + payable + "' AND tenant_id = ?", tenant)).containsExactly("4000");
    }

    @Test
    void a_second_approval_of_the_same_order_is_refused_and_posts_nothing_more() {
        UUID order = orderNeedingReview();
        assertThat(approvals.approve(tenant, order, seller, 5000)).isEqualTo(OrderApprovalService.Outcome.APPROVED);
        String after = fingerprint();
        assertThat(approvals.approve(tenant, order, seller, 7000)).isEqualTo(OrderApprovalService.Outcome.NOT_REVIEWABLE);
        assertThat(fingerprint()).isEqualTo(after);
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND kind = 'ORDER_ACCEPTED'", tenant, null)).isEqualTo(1);
    }

    @Test
    void the_total_must_be_between_one_and_the_largest_amount() {
        UUID order = orderNeedingReview();
        String before = fingerprint();
        for (long bad : new long[]{0, -1, -5000, 1_000_000_000_001L, Long.MAX_VALUE, Long.MIN_VALUE}) {
            assertThat(approvals.approve(tenant, order, seller, bad)).as("total " + bad).isEqualTo(OrderApprovalService.Outcome.TOTAL_OUT_OF_RANGE);
        }
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("NEEDS_REVIEW");
        assertThat(approvals.approve(tenant, order, seller, 1_000_000_000_000L)).as("the largest").isEqualTo(OrderApprovalService.Outcome.APPROVED);
        UUID small = orderNeedingReview();
        assertThat(approvals.approve(tenant, small, seller, 1)).as("the smallest").isEqualTo(OrderApprovalService.Outcome.APPROVED);
    }

    @Test
    void the_range_is_checked_after_the_user_and_the_order_so_a_stranger_learns_nothing() {
        UUID order = orderNeedingReview();
        assertThat(approvals.approve(tenant, order, helper, -1)).isEqualTo(OrderApprovalService.Outcome.NOT_A_SELLER);
        assertThat(approvals.approve(tenant, UUID.randomUUID(), seller, -1)).isEqualTo(OrderApprovalService.Outcome.NOT_FOUND);
    }

    @Test
    void a_missing_argument_is_refused_by_name() {
        assertThatNullPointerException().isThrownBy(() -> approvals.approve(tenant, null, seller, 5000)).withMessage("orderId");
        assertThatNullPointerException().isThrownBy(() -> approvals.approve(tenant, UUID.randomUUID(), null, 5000)).withMessage("actor");
    }

    @Test
    void a_failure_after_any_step_leaves_nothing_and_the_retry_approves_once() {
        for (OrderApprovalService.Step step : OrderApprovalService.Step.values()) {
            MatchWorld world = new MatchWorld();
            UUID s = MatchingFixture.user(world.tenant, "SELLER");
            IngestionResult r = f.orders.ingest(world.tenant, s, id(), "Order: A-1042\nName: Ana Kamga\nPhone: " + PHONE + "\n2 x rice\n1 x mystery");
            UUID order = r.orderId();
            String before = MatchInvariants.fingerprint(world.tenant);
            OrderApprovalService failing = new OrderApprovalService(MatchingFixture.POOL, f.repository, f.orderRepository, f.matcher, f.clock, (at, c) -> {
                if (at == step) {
                    throw new IllegalStateException("injected failure after " + at);
                }
            });
            assertThatThrownBy(() -> failing.approve(world.tenant, order, s, 5000)).as("after " + step).hasMessageContaining("injected failure after " + step);
            assertThat(MatchInvariants.fingerprint(world.tenant)).as("nothing is left after " + step).isEqualTo(before);
            assertThat(MatchingFixture.status("orders", order)).isEqualTo("NEEDS_REVIEW");

            assertThat(approvals.approve(world.tenant, order, s, 5000)).as("the retry after " + step).isEqualTo(OrderApprovalService.Outcome.APPROVED);
            assertThat(MatchingFixture.ledger(world.tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000");
        }
    }

    @Test
    void a_posting_the_database_refuses_leaves_the_order_in_review() {
        UUID order = orderNeedingReview();
        // an ORDER_ACCEPTED posting for this order already exists (as an older version could have left it): the unique index refuses a second one
        MatchWorld.forced(c -> {
            ChartOfAccounts.Accounts a = ChartOfAccounts.ensure(c, tenant);
            UUID txn = UUID.randomUUID();
            MatchWorld.run(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'ORDER_ACCEPTED', 'ORDER', ?)", txn, tenant, order);
            MatchWorld.run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, 100, 0)", UUID.randomUUID(), txn, a.receivable());
            MatchWorld.run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, 0, 100)", UUID.randomUUID(), txn, a.revenue());
        });
        assertThatThrownBy(() -> approvals.approve(tenant, order, seller, 5000)).isInstanceOf(IllegalStateException.class);
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("NEEDS_REVIEW");
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'ORDER_APPROVED'", tenant, null)).isZero();
    }

    @Test
    void sixteen_threads_approving_one_order_approve_it_once() throws Exception {
        UUID order = orderNeedingReview();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<OrderApprovalService.Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            long total = 5000 + i;
            Callable<OrderApprovalService.Outcome> task = () -> {
                go.await();
                return approvals.approve(tenant, order, seller, total);
            };
            futures.add(pool.submit(task));
        }
        go.countDown();
        List<OrderApprovalService.Outcome> outcomes = new ArrayList<>();
        for (Future<OrderApprovalService.Outcome> future : futures) {
            outcomes.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(outcomes).filteredOn(o -> o == OrderApprovalService.Outcome.APPROVED).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o == OrderApprovalService.Outcome.NOT_REVIEWABLE).hasSize(15);
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND kind = 'ORDER_ACCEPTED'", tenant, null)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'ORDER_APPROVED'", tenant, null)).isEqualTo(1);
        MatchInvariants.assertHold(tenant);
    }
}
