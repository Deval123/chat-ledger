package dev.chatledger.matching;

import dev.chatledger.extraction.TextKeys;
import dev.chatledger.orders.IngestionResult;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.chatledger.matching.MatchingFixture.count;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The order's own posting: a payable order is a receivable from the moment it is stored (debit Accounts receivable, credit Sales revenue),
 * in the same transaction as the order. If the posting fails the order is not stored, the caller gets an exception, and a retry with the same
 * external id stores it and posts it exactly once.
 *
 * <p>Requirements: 4.1, 1.3. Task 6.2 (decision D1).
 */
class OrderPostingIT {

    private final MatchingFixture f = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;

    private long orders() {
        return count("SELECT count(*) FROM orders WHERE tenant_id = ?", tenant, null);
    }

    private long postings() {
        return count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND kind = 'ORDER_ACCEPTED'", tenant, null);
    }

    // ── What is posted ─────────────────────────────────────────────────────

    @Test
    void a_payable_order_is_a_receivable_the_moment_it_is_stored() {
        IngestionResult r = f.orders.ingest(tenant, null, id(), MatchingFixture.orderText("A-1042", "Ana Kamga", MatchingFixture.PHONE, 6000));
        assertThat(r.outcome()).isEqualTo(IngestionResult.Outcome.CREATED);
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D6000", "ORDER_ACCEPTED 4000 C6000");
        assertThat(MatchingFixture.balance(tenant, "1100")).as("6,000 is owed to the seller").isEqualTo(6000);
        assertThat(MatchingFixture.balance(tenant, "4000")).isEqualTo(-6000);
        assertThat(MatchingFixture.trialBalance(tenant)).isZero();
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ? AND ref_id = ?", tenant, r.orderId())).isEqualTo(1);
    }

    @Test
    void an_order_that_is_not_payable_posts_nothing() {
        w.run("INSERT INTO catalog_item(id, tenant_id, name_key, display_name, unit_minor) VALUES (?, ?, ?, 'Rice', 1500)", UUID.randomUUID(), tenant, TextKeys.key("rice"));
        IngestionResult review = f.orders.ingest(tenant, null, id(), "2 x rice\n1 x mystery");
        IngestionResult unparsed = f.orders.ingest(tenant, null, id(), "Hello, could you send two boxes of tomatoes tomorrow please?");
        assertThat(review.outcome()).isEqualTo(IngestionResult.Outcome.CREATED_NEEDS_REVIEW);
        assertThat(unparsed.outcome()).isEqualTo(IngestionResult.Outcome.CREATED_UNPARSED);
        assertThat(MatchingFixture.ledger(tenant)).isEmpty();
        assertThat(count("SELECT count(*) FROM account WHERE tenant_id = ?", tenant, null)).as("not even the accounts").isZero();
    }

    @Test
    void the_four_accounts_are_made_once_and_every_order_posts_to_them() {
        f.orders.ingest(tenant, null, id(), MatchingFixture.orderText(null, null, null, 1000));
        f.orders.ingest(tenant, null, id(), MatchingFixture.orderText(null, null, null, 2000));
        assertThat(count("SELECT count(*) FROM account WHERE tenant_id = ?", tenant, null)).isEqualTo(4);
        assertThat(MatchingFixture.balance(tenant, "1100")).isEqualTo(3000);
        assertThat(postings()).isEqualTo(2);
    }

    // ── When the posting fails ─────────────────────────────────────────────

    private void block(String name) throws SQLException {
        try (Connection c = MatchingFixture.DB.migrateDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE ledger_txn ADD CONSTRAINT " + name + " CHECK (tenant_id <> '" + tenant + "'::uuid) NOT VALID");
        }
    }

    private void unblock(String name) throws SQLException {
        try (Connection c = MatchingFixture.DB.migrateDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE ledger_txn DROP CONSTRAINT " + name);
        }
    }

    @Test
    void if_the_posting_fails_the_caller_gets_an_exception_the_order_is_not_stored_and_a_retry_stores_and_posts_it_once() throws SQLException {
        String externalId = id();
        String text = MatchingFixture.orderText("A-1042", "Ana Kamga", MatchingFixture.PHONE, 5000);
        String name = "block_" + UUID.randomUUID().toString().replace("-", "");
        block(name);

        // What the caller sees: an IllegalStateException with a fixed message and no text from the order.
        assertThatThrownBy(() -> f.orders.ingest(tenant, null, externalId, text)).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Could not post an order to the ledger").message().doesNotContain("Ana").doesNotContain("A-1042").doesNotContain("5000");
        // What is left: nothing at all. The order, its customer, its lines, its audit row, its accounts and its posting rolled back together.
        assertThat(orders()).isZero();
        assertThat(count("SELECT count(*) FROM customer WHERE tenant_id = ?", tenant, null)).isZero();
        assertThat(count("SELECT count(*) FROM order_line WHERE tenant_id = ?", tenant, null)).isZero();
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ?", tenant, null)).isZero();
        assertThat(count("SELECT count(*) FROM account WHERE tenant_id = ?", tenant, null)).isZero();
        assertThat(postings()).isZero();
        assertThat(f.orderRepository.findByExternalId(tenant, externalId)).isEmpty();

        // The retry, with the same external id, once the fault is gone.
        unblock(name);
        IngestionResult retry = f.orders.ingest(tenant, null, externalId, text);
        assertThat(retry.outcome()).isEqualTo(IngestionResult.Outcome.CREATED);
        assertThat(orders()).isEqualTo(1);
        assertThat(postings()).isEqualTo(1);
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000");

        // A second retry is a replay: it posts nothing.
        IngestionResult again = f.orders.ingest(tenant, null, externalId, text);
        assertThat(again.outcome()).isEqualTo(IngestionResult.Outcome.DUPLICATE);
        assertThat(again.orderId()).isEqualTo(retry.orderId());
        assertThat(postings()).isEqualTo(1);
        assertThat(MatchingFixture.trialBalance(tenant)).isZero();
    }

    @Test
    void an_unusable_chart_of_accounts_stops_the_order_the_same_way() {
        w.run("INSERT INTO account(id, tenant_id, code, name, type) VALUES (?, ?, '1100', 'Wrong', 'LIABILITY')", UUID.randomUUID(), tenant);
        String externalId = id();
        String text = MatchingFixture.orderText(null, null, null, 5000);
        assertThatThrownBy(() -> f.orders.ingest(tenant, null, externalId, text)).isExactlyInstanceOf(IllegalStateException.class);
        assertThat(orders()).isZero();
        assertThat(postings()).isZero();
        w.run("UPDATE account SET type = 'ASSET' WHERE tenant_id = ? AND code = '1100'", tenant);
        assertThat(f.orders.ingest(tenant, null, externalId, text).outcome()).isEqualTo(IngestionResult.Outcome.CREATED);
        assertThat(postings()).isEqualTo(1);
    }

    @Test
    void sixteen_simultaneous_submissions_of_one_message_make_one_order_and_one_posting() throws Exception {
        String externalId = id();
        String text = MatchingFixture.orderText("A-1042", "Ana Kamga", MatchingFixture.PHONE, 5000);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IngestionResult>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return f.orders.ingest(tenant, null, externalId, text);
            }));
        }
        start.countDown();
        List<IngestionResult> results = new ArrayList<>();
        for (Future<IngestionResult> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();

        assertThat(results).filteredOn(r -> r.outcome() == IngestionResult.Outcome.CREATED).hasSize(1);
        assertThat(results).filteredOn(r -> r.outcome() == IngestionResult.Outcome.DUPLICATE).hasSize(15);
        assertThat(orders()).isEqualTo(1);
        assertThat(postings()).isEqualTo(1);
        assertThat(MatchingFixture.balance(tenant, "1100")).isEqualTo(5000);
        assertThat(MatchingFixture.trialBalance(tenant)).isZero();
    }

    @Test
    void sixteen_threads_retrying_after_a_failure_post_the_order_once() throws Exception {
        String externalId = id();
        String text = MatchingFixture.orderText("A-1042", "Ana Kamga", MatchingFixture.PHONE, 5000);
        String name = "block_" + UUID.randomUUID().toString().replace("-", "");
        block(name);
        assertThatThrownBy(() -> f.orders.ingest(tenant, null, externalId, text)).isInstanceOf(IllegalStateException.class);
        unblock(name);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IngestionResult>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return f.orders.ingest(tenant, null, externalId, text);
            }));
        }
        start.countDown();
        for (Future<IngestionResult> future : futures) {
            future.get();
        }
        pool.shutdown();
        assertThat(orders()).isEqualTo(1);
        assertThat(postings()).as("the failed attempt left nothing, the retries posted once").isEqualTo(1);
    }

    // ── The safety net in the matcher ──────────────────────────────────────

    @Test
    void an_order_stored_without_its_posting_is_posted_before_its_first_match_and_never_twice() {
        UUID order = w.order(5000, "PENDING_PAYMENT", "A-1", null);   // written by hand: no posting
        UUID first = w.payment(2000, "RECEIVED");
        UUID second = w.payment(3000, "RECEIVED");
        assertThat(postings()).isZero();
        f.matcher.apply(tenant, order, first, MatchingService.Mode.CONFIRM, w.user);
        assertThat(postings()).isEqualTo(1);
        f.matcher.apply(tenant, order, second, MatchingService.Mode.CONFIRM, w.user);
        assertThat(postings()).as("the second match finds the posting and adds none").isEqualTo(1);
        assertThat(MatchingFixture.balance(tenant, "1100")).isZero();
        assertThat(MatchingFixture.trialBalance(tenant)).isZero();
    }

    @Test
    void the_posting_ensured_by_the_matcher_is_the_same_event_as_the_one_ingestion_makes() {
        IngestionResult r = f.orders.ingest(tenant, null, id(), MatchingFixture.orderText(null, null, null, 5000));
        UUID payment = w.payment(5000, "RECEIVED");
        f.matcher.apply(tenant, r.orderId(), payment, MatchingService.Mode.CONFIRM, w.user);
        assertThat(postings()).isEqualTo(1);
    }
}
