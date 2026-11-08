package dev.chatledger.matching;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inserting a match, or any row that refers to an order or a payment, takes a "key share" lock on the row it refers to (that is how
 * PostgreSQL checks a foreign key). A transaction that locks the same row with {@code FOR UPDATE} has to wait for it, and a transaction
 * that waits there while holding the payment's lock can wait for ever for one that holds the order's key share and wants the
 * payment's: a deadlock between the engine storing a suggestion and a seller's decision. The matcher locks rows {@code FOR NO KEY
 * UPDATE} instead, which it can do because it never changes a key column, and which does not wait for a key share.
 *
 * <p>The proof is not a deadlock that happened not to occur: one transaction holds a key share on the order and the payment, as the
 * check of a foreign key does, and the matcher must still be able to take its locks.
 *
 * <p>Requirements: 3, 4. Task 6.3 (found by the storm test of {@code SellerActionsConcurrencyIT}).
 */
class ForeignKeyChecksDoNotBlockIT {

    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final UUID seller = MatchingFixture.user(tenant, "SELLER");

    /** A connection that holds a key share on the rows, as a foreign-key check does, until it is closed. */
    private static Connection keyShare(UUID orderId, UUID paymentId) throws SQLException {
        Connection c = MatchWorld.DB.migrateDataSource().getConnection();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM orders WHERE id = ? FOR KEY SHARE")) {
            ps.setObject(1, orderId);
            ps.executeQuery();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM payment WHERE id = ? FOR KEY SHARE")) {
            ps.setObject(1, paymentId);
            ps.executeQuery();
        }
        return c;
    }

    @Test
    void applying_a_match_takes_its_locks_while_a_foreign_key_check_holds_a_key_share_on_both_rows() throws Exception {
        UUID order = w.order(5000, "PENDING_PAYMENT", "A-1", null);
        UUID payment = w.payment(5000, "RECEIVED");
        CountDownLatch locked = new CountDownLatch(1);
        MatchingFixture probe = new MatchingFixture(MatchConfig.defaults(), (step, c) -> {
            if (step == MatchingService.ApplyStep.LOCKED) {
                locked.countDown();
            }
        }, false);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection check = keyShare(order, payment)) {
            Future<MatchingService.ApplyResult> apply = pool.submit(() -> probe.matcher.apply(tenant, order, payment, MatchingService.Mode.CONFIRM, seller));
            assertThat(locked.await(15, TimeUnit.SECONDS)).as("the payment and the order were locked while the key share was held").isTrue();
            // and it commits with the key share still held: the commit-time check of V016 locks the order too, and must not wait for it
            assertThat(apply.get(30, TimeUnit.SECONDS).outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
            check.rollback();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void an_unmatch_takes_its_locks_while_a_foreign_key_check_holds_a_key_share_on_both_rows() throws Exception {
        MatchingFixture live = new MatchingFixture();
        UUID order = live.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("A-1042", "Ana Kamga", PHONE, 5000)).orderId();
        UUID payment = live.payments.ingest(tenant, seller, id(), live.sms(5000, "Ana Kamga", PHONE, "A-1042")).paymentId();
        UUID match = live.repository.forPayment(tenant, payment).get(0).id();
        CountDownLatch locked = new CountDownLatch(1);
        MatchingFixture probe = new MatchingFixture((step, c) -> {
            if (step == MatchingService.ActionStep.LOCKED) {
                locked.countDown();
            }
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection check = keyShare(order, payment)) {
            Future<MatchingService.Decision> unmatch = pool.submit(() -> probe.matcher.unmatch(tenant, match, seller));
            assertThat(locked.await(15, TimeUnit.SECONDS)).as("the three rows were locked while the key share was held").isTrue();
            assertThat(unmatch.get(30, TimeUnit.SECONDS).done()).as("committed with the key share still held").isTrue();
            check.rollback();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void a_rejection_takes_its_lock_while_a_key_share_is_held() throws Exception {
        UUID order = w.order(5000, "PENDING_PAYMENT", "A-1", null);
        UUID payment = w.payment(5000, "RECEIVED");
        UUID suggestion = w.suggested(order, payment);
        CountDownLatch locked = new CountDownLatch(1);
        MatchingFixture probe = new MatchingFixture((step, c) -> {
            if (step == MatchingService.ActionStep.LOCKED) {
                locked.countDown();
            }
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection check = keyShare(order, payment)) {
            Future<MatchingService.Decision> reject = pool.submit(() -> probe.matcher.reject(tenant, suggestion, seller));
            assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();
            check.rollback();
            assertThat(reject.get(30, TimeUnit.SECONDS).done()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
