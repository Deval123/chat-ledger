package dev.chatledger.matching;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one lock order: payment, then order, then match, in every operation that takes more than one lock. Two operations that lock the
 * same rows in opposite orders can wait for each other for ever; the proof here is not a deadlock that happened to be avoided but a
 * look, from another connection, at which rows a transaction holds at the moment it has taken the payment's lock and not yet the
 * order's. All names and numbers are synthetic.
 *
 * <p>Requirements: 3, 4. Task 6.3.
 */
class LockOrderIT {

    private static boolean lockedByAnother(String table, UUID id) throws SQLException {
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + table + " WHERE id = ? FOR UPDATE NOWAIT")) {
                ps.setObject(1, id);
                ps.executeQuery();
                return false;
            } catch (SQLException e) {
                return "55P03".equals(e.getSQLState());
            } finally {
                c.rollback();
            }
        }
    }

    /** What another connection saw at the moment the payment's lock was taken: {payment locked, order locked}. */
    private record Seen(boolean payment, boolean order) {
    }

    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final UUID seller = MatchingFixture.user(tenant, "SELLER");

    @Test
    void an_application_of_a_match_takes_the_payment_lock_before_the_order_lock() {
        UUID order = w.order(5000, "PENDING_PAYMENT", "A-1", null);
        UUID payment = w.payment(5000, "RECEIVED");
        AtomicReference<Seen> seen = new AtomicReference<>();
        MatchingFixture probe = new MatchingFixture(MatchConfig.defaults(), (step, c) -> {
            if (step == MatchingService.ApplyStep.PAYMENT_LOCKED) {
                seen.set(new Seen(lockedByAnother("payment", payment), lockedByAnother("orders", order)));
            }
        }, false);

        probe.matcher.apply(tenant, order, payment, MatchingService.Mode.CONFIRM, seller);

        assertThat(seen.get()).isEqualTo(new Seen(true, false));
    }

    @Test
    void an_unmatch_takes_the_payment_lock_before_the_order_lock_and_the_order_lock_before_the_match_lock() {
        MatchingFixture live = new MatchingFixture();
        UUID order = live.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("A-1042", "Ana Kamga", PHONE, 5000)).orderId();
        UUID payment = live.payments.ingest(tenant, seller, id(), live.sms(5000, "Ana Kamga", PHONE, "A-1042")).paymentId();
        UUID match = live.repository.forPayment(tenant, payment).get(0).id();
        AtomicReference<Seen> atPayment = new AtomicReference<>();
        AtomicReference<Boolean> matchAtOrder = new AtomicReference<>();
        MatchingFixture probe = new MatchingFixture((step, c) -> {
            if (step == MatchingService.ActionStep.PAYMENT_LOCKED) {
                atPayment.set(new Seen(lockedByAnother("payment", payment), lockedByAnother("orders", order)));
                matchAtOrder.set(lockedByAnother("match", match));
            }
        });

        assertThat(probe.matcher.unmatch(tenant, match, seller).done()).isTrue();

        assertThat(atPayment.get()).isEqualTo(new Seen(true, false));
        assertThat(matchAtOrder.get()).as("the match is locked last").isFalse();
    }

    @Test
    void by_the_time_everything_is_locked_an_unmatch_holds_all_three_rows() {
        MatchingFixture live = new MatchingFixture();
        UUID order = live.orders.ingest(tenant, seller, id(), MatchingFixture.orderText("A-1042", "Ana Kamga", PHONE, 5000)).orderId();
        UUID payment = live.payments.ingest(tenant, seller, id(), live.sms(5000, "Ana Kamga", PHONE, "A-1042")).paymentId();
        UUID match = live.repository.forPayment(tenant, payment).get(0).id();
        AtomicReference<String> held = new AtomicReference<>();
        MatchingFixture probe = new MatchingFixture((step, c) -> {
            if (step == MatchingService.ActionStep.LOCKED) {
                held.set(lockedByAnother("payment", payment) + " " + lockedByAnother("orders", order) + " " + lockedByAnother("match", match));
            }
        });

        probe.matcher.unmatch(tenant, match, seller);

        assertThat(held.get()).isEqualTo("true true true");
    }
}
