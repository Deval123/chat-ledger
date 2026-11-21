package dev.chatledger.matching;

import dev.chatledger.tenancy.TenantScope;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database's own rules for cancel and dismiss (V021), each proved by writing as the application role, in a tenant scope, straight to
 * the tables, without the service: a code path that forgot a check would be refused here, at the statement or at commit. Every rule has
 * its own test, and each test is the one its mutant fails (task 12b, the owner's condition 2).
 */
class CancelDismissGuardsIT {

    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final TenantScope app = new TenantScope(MatchingFixture.POOL);

    @FunctionalInterface
    interface Sql {
        void run(Connection c) throws SQLException;
    }

    /** One transaction as the application role, committed. */
    private void asApp(Sql work) throws SQLException {
        app.call(tenant, c -> {
            work.run(c);
            return null;
        });
    }

    private UUID postedOrder() {
        return BooksCheckIT.postedOrder(w, 4000);
    }

    // ── 1. orders_status_transition ─────────────────────────────────────────

    @Test
    void g1_the_one_move_to_cancelled_is_from_pending_payment_with_no_active_match() {
        UUID order = postedOrder();
        // An active match on a PENDING_PAYMENT order cannot exist in a consistent database; forced here, so the move itself is shown to look.
        UUID payment = w.payment(4000, "MATCHED");
        MatchWorld.forced(c -> MatchWorld.run(c, "INSERT INTO match(id, tenant_id, order_id, payment_id, state, amount_minor, excess_minor, score, "
                + "reasons_json, ledger_txn_id) VALUES (?, ?, ?, ?, 'AUTO', 4000, 0, 100, '[]', ?)", UUID.randomUUID(), tenant, order, payment, UUID.randomUUID()));
        assertThatThrownBy(() -> asApp(c -> {
            BooksCheckIT.reverseAcceptance(c, tenant, order);
            MatchWorld.run(c, "UPDATE orders SET status = 'CANCELLED' WHERE id = ?", order);
        })).hasMessageContaining("has an active match and cannot be cancelled");
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void g1_no_other_move_reaches_or_leaves_cancelled() {
        for (String from : new String[]{"PARTIALLY_PAID", "PAID", "NEEDS_REVIEW", "UNPARSED"}) {
            UUID order = "NEEDS_REVIEW".equals(from) || "UNPARSED".equals(from) ? w.orderWithoutTotal(from) : w.order(4000, from, null, null);
            if ("NEEDS_REVIEW".equals(from)) {
                MatchWorld.forced(c -> MatchWorld.run(c, "UPDATE orders SET total_minor = 4000 WHERE id = ?", order));
            }
            assertThatThrownBy(() -> asApp(c -> MatchWorld.run(c, "UPDATE orders SET status = 'CANCELLED' WHERE id = ?", order)))
                    .as(from).hasMessageContaining("order status change " + from + " to CANCELLED is not allowed");
        }
        UUID cancelled = w.order(4000, "PENDING_PAYMENT", null, null);
        MatchWorld.forced(c -> MatchWorld.run(c, "UPDATE orders SET status = 'CANCELLED' WHERE id = ?", cancelled));
        for (String to : new String[]{"PENDING_PAYMENT", "PAID"}) {
            assertThatThrownBy(() -> asApp(c -> MatchWorld.run(c, "UPDATE orders SET status = '" + to + "' WHERE id = ?", cancelled)))
                    .as(to).hasMessageContaining("order status change CANCELLED to " + to + " is not allowed");
        }
    }

    @Test
    void g1_a_dismissed_order_is_never_made_payable() throws SQLException {
        UUID review = w.orderWithoutTotal("NEEDS_REVIEW");
        asApp(c -> MatchWorld.run(c, "INSERT INTO record_dismissal(id, tenant_id, order_id, dismissed_by) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), tenant, review, w.user));
        assertThatThrownBy(() -> asApp(c -> MatchWorld.run(c, "UPDATE orders SET total_minor = 4000, status = 'PENDING_PAYMENT' WHERE id = ?", review)))
                .hasMessageContaining("was dismissed and cannot become payable");
        assertThat(MatchingFixture.status("orders", review)).isEqualTo("NEEDS_REVIEW");
    }

    // ── 2. orders_assert_status_consistent ─────────────────────────────────

    /** The rewrite must let a correct cancel commit: the V016 version expected PENDING_PAYMENT for an order with nothing applied. */
    @Test
    void g2_a_correct_cancel_commits() {
        UUID order = postedOrder();
        assertThatCode(() -> asApp(c -> {
            BooksCheckIT.reverseAcceptance(c, tenant, order);
            MatchWorld.run(c, "UPDATE orders SET status = 'CANCELLED' WHERE id = ?", order);
        })).doesNotThrowAnyException();
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("CANCELLED");
        MatchInvariants.assertHold(tenant);
    }

    // ── 3. orders_assert_cancel_reversed ───────────────────────────────────

    @Test
    void g3_a_cancel_without_the_reversal_of_the_acceptance_is_refused_at_commit() {
        UUID order = postedOrder();
        assertThatThrownBy(() -> asApp(c -> MatchWorld.run(c, "UPDATE orders SET status = 'CANCELLED' WHERE id = ?", order)))
                .hasMessageContaining("was cancelled without the reversal of its ORDER_ACCEPTED posting");
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D4000", "ORDER_ACCEPTED 4000 C4000");
    }

    // ── 4. ledger_assert_acceptance_reversal ──────────────────────────────

    @Test
    void g4_the_acceptance_of_an_order_that_stays_payable_cannot_be_reversed() {
        UUID order = postedOrder();
        assertThatThrownBy(() -> asApp(c -> BooksCheckIT.reverseAcceptance(c, tenant, order)))
                .hasMessageContaining("posting of order " + order + " was reversed but the order is PENDING_PAYMENT");
        assertThat(MatchingFixture.ledger(tenant)).containsExactly("ORDER_ACCEPTED 1100 D4000", "ORDER_ACCEPTED 4000 C4000");
    }

    // ── 5. record_dismissal ────────────────────────────────────────────────

    private void dismiss(UUID order, UUID payment) throws SQLException {
        asApp(c -> MatchWorld.run(c, "INSERT INTO record_dismissal(id, tenant_id, order_id, payment_id, dismissed_by) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), tenant, order, payment, w.user));
    }

    @Test
    void g5_only_a_record_that_never_reached_the_books_can_be_dismissed() {
        UUID payable = postedOrder();
        UUID cancelled = w.order(4000, "PENDING_PAYMENT", null, null);
        MatchWorld.forced(c -> MatchWorld.run(c, "UPDATE orders SET status = 'CANCELLED' WHERE id = ?", cancelled));
        assertThatThrownBy(() -> dismiss(payable, null)).hasMessageContaining("is PENDING_PAYMENT and cannot be dismissed");
        assertThatThrownBy(() -> dismiss(cancelled, null)).hasMessageContaining("is CANCELLED and cannot be dismissed");
        for (String status : new String[]{"RECEIVED", "MATCHED", "UNMATCHED"}) {
            UUID payment = w.payment(4000, status);
            assertThatThrownBy(() -> dismiss(null, payment)).as(status).hasMessageContaining("is " + status + " and cannot be dismissed");
        }
        assertThatThrownBy(() -> dismiss(null, w.payment(4000, "UNVERIFIED", false))).hasMessageContaining("is UNVERIFIED and cannot be dismissed");
        assertThat(MatchingFixture.count("SELECT count(*) FROM record_dismissal WHERE tenant_id = ?", tenant, null)).isZero();
    }

    @Test
    void g5_a_dismissal_names_exactly_one_record_and_happens_once() throws SQLException {
        UUID order = w.orderWithoutTotal("UNPARSED");
        UUID payment = w.unparsedPayment();
        assertThatThrownBy(() -> dismiss(null, null)).as("no record: the trigger, which runs first, finds none").hasMessageContaining("is missing and cannot be dismissed");
        assertThatThrownBy(() -> dismiss(order, payment)).hasMessageContaining("chk_record_dismissal_one_subject");
        dismiss(order, null);
        dismiss(null, payment);
        assertThatThrownBy(() -> dismiss(order, null)).hasMessageContaining("uq_record_dismissal_order");
        assertThatThrownBy(() -> dismiss(null, payment)).hasMessageContaining("uq_record_dismissal_payment");
    }

    @Test
    void g5_a_dismissal_is_never_changed_and_stays_in_its_tenant() throws SQLException {
        UUID order = w.orderWithoutTotal("UNPARSED");
        dismiss(order, null);
        assertThatThrownBy(() -> asApp(c -> MatchWorld.run(c, "UPDATE record_dismissal SET dismissed_at = now() WHERE order_id = ?", order)))
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asApp(c -> MatchWorld.run(c, "DELETE FROM record_dismissal WHERE order_id = ?", order)))
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> w.run("DELETE FROM record_dismissal WHERE order_id = ?", order)).as("not even the schema owner")
                .hasMessageContaining("append-only");
        MatchWorld other = new MatchWorld();
        UUID theirs = other.orderWithoutTotal("UNPARSED");
        assertThatThrownBy(() -> dismiss(theirs, null)).as("another tenant's order").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> app.call(tenant, c -> {
            MatchWorld.run(c, "INSERT INTO record_dismissal(id, tenant_id, order_id, dismissed_by) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), other.tenant, theirs, other.user);
            return null;
        })).as("a row for another tenant: the trigger's own read is tenant-scoped, so it finds nothing").hasMessageContaining("is missing and cannot be dismissed");
    }
}
