package dev.chatledger.matching;

import dev.chatledger.orders.LogCapture;
import dev.chatledger.payments.PaymentIngestionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static dev.chatledger.matching.MatchingFixture.PHONE;
import static dev.chatledger.matching.MatchingFixture.count;
import static dev.chatledger.matching.MatchingFixture.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The edges of the apply step and the engine that the main flows do not reach: arguments that are missing, orders that are not open
 * although they have a total, an automatic decision that the locked data overturns, a suggestion that the seller or the system decided
 * while a confirmation was running, and what each of those writes to the log. All names and numbers are synthetic.
 *
 * <p>Requirements: 3.1 to 3.6, 4.1. Task 6.2.
 */
class MatchingEdgeCasesIT {

    private final MatchingFixture f = new MatchingFixture();
    private final MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
    private final MatchWorld w = new MatchWorld();
    private final UUID tenant = w.tenant;
    private final UUID seller = MatchingFixture.user(tenant, "SELLER");

    private UUID order(String ref, String name, String phone, long total) {
        return quiet.orders.ingest(tenant, seller, id(), MatchingFixture.orderText(ref, name, phone, total)).orderId();
    }

    private long matches(String state) {
        return count("SELECT count(*) FROM match WHERE tenant_id = ? AND state = ?", tenant, state);
    }

    @Test
    void a_missing_argument_is_refused_by_name_before_anything_is_read() {
        UUID order = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        assertThatNullPointerException().isThrownBy(() -> f.matcher.apply(null, order, payment, MatchingService.Mode.AUTO, null)).withMessage("tenantId");
        assertThatNullPointerException().isThrownBy(() -> f.matcher.apply(tenant, null, payment, MatchingService.Mode.AUTO, null)).withMessage("orderId");
        assertThatNullPointerException().isThrownBy(() -> f.matcher.apply(tenant, order, null, MatchingService.Mode.AUTO, null)).withMessage("paymentId");
        assertThatNullPointerException().isThrownBy(() -> f.matcher.apply(tenant, order, payment, null, null)).withMessage("mode");
    }

    @Test
    void an_order_that_is_not_open_is_never_matched_whatever_its_total() {
        for (String status : List.of("PAID", "CANCELLED", "NEEDS_REVIEW")) {
            UUID order = w.order(5000, "PENDING_PAYMENT", "S-" + status, null);
            MatchWorld.forced(c -> MatchWorld.run(c, "UPDATE orders SET status = ? WHERE id = ?", status, order));
            UUID payment = w.payment(1000, "RECEIVED");
            MatchingService.ApplyResult r = f.matcher.apply(tenant, order, payment, MatchingService.Mode.CONFIRM, seller);
            assertThat(r.outcome()).as(status).isEqualTo(MatchingService.Outcome.NOT_APPLICABLE);
            assertThat(r.reason()).as(status).isEqualTo(MatchingService.Reason.ORDER_NOT_OPEN);
            assertThat(MatchingFixture.status("payment", payment)).isEqualTo("RECEIVED");
            assertThat(MatchingFixture.status("orders", order)).isEqualTo(status);
        }
        assertThat(MatchingFixture.ledger(tenant)).isEmpty();
    }

    @Test
    void an_automatic_decision_that_the_locked_data_overturns_is_stored_as_a_suggestion() {
        UUID order = order("A-1042", null, null, 5000);   // the truth: nobody is named on the order
        UUID payment = quiet.payments.ingest(tenant, seller, id(), quiet.sms(5000, "Ana Kamga", PHONE, "A-1042")).paymentId();
        PaymentFacts paid = f.repository.paymentCandidate(tenant, payment).orElseThrow().facts();
        // A repository that describes the order as if its customer were the sender: the engine plans an automatic match from this
        // description, then the apply step re-reads the order under the lock and finds the truth.
        MatchRepository liar = new MatchRepository(MatchWorld.DB.appDataSource()) {
            @Override
            public List<OrderFacts> openOrders(UUID tenantId, String currency, int limit) {
                return super.openOrders(tenantId, currency, limit).stream().map(o -> new OrderFacts(o.id(), o.totalMinor(), o.appliedMinor(), o.currency(),
                        o.createdAt(), o.orderRef(), paid.senderName(), paid.senderPhoneHash(), paid.senderLast4(), false)).toList();
            }
        };
        MatchingService optimistic = new MatchingService(MatchWorld.DB.appDataSource(), liar, MatchConfig.defaults(), new MatchEvents(), f.clock);
        MatchingService.Run honest = f.matcher.onPaymentRecorded(tenant, payment);
        assertThat(honest.kind()).as("on the truth alone this is not automatic").isEqualTo(MatchingService.RunKind.SUGGESTED);
        MatchWorld.forced(c -> MatchWorld.run(c, "DELETE FROM match WHERE tenant_id = ?", tenant));

        try (LogCapture logs = new LogCapture()) {
            MatchingService.Run run = optimistic.onPaymentRecorded(tenant, payment);
            assertThat(run.kind()).isEqualTo(MatchingService.RunKind.SUGGESTED);
            assertThat(run.suggestions()).isEqualTo(1);
            assertThat(logs.text()).contains("automatic match became a suggestion", order.toString(), payment.toString());
        }
        assertThat(matches("SUGGESTED")).isEqualTo(1);
        assertThat(matches("AUTO")).isZero();
        assertThat(MatchingFixture.status("payment", payment)).isEqualTo("RECEIVED");
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
        assertThat(MatchingFixture.ledger(tenant)).as("a suggestion posts nothing").hasSize(2);
    }

    @Test
    void a_refusal_is_logged_with_ids_and_a_code_only() {
        try (LogCapture logs = new LogCapture()) {
            UUID order = UUID.randomUUID();
            UUID payment = UUID.randomUUID();
            f.matcher.apply(tenant, order, payment, MatchingService.Mode.CONFIRM, seller);
            assertThat(logs.text()).contains("match not applicable", order.toString(), payment.toString(), "NOT_FOUND");
        }
    }

    @Test
    void a_pair_the_seller_rejected_is_not_proposed_again_and_nothing_is_logged_as_stored() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        PaymentIngestionResult payment = quiet.payments.ingest(tenant, seller, id(), quiet.forwarded(5000, "Ana Kamga", "1234"));
        w.rejected(order, payment.paymentId(), true);
        try (LogCapture logs = new LogCapture()) {
            MatchingService.Run run = f.matcher.onPaymentRecorded(tenant, payment.paymentId());
            assertThat(run.kind()).isEqualTo(MatchingService.RunKind.SUGGESTED);
            assertThat(run.suggestions()).isZero();
            assertThat(logs.text()).doesNotContain("match suggestions stored");
        }
        assertThat(matches("SUGGESTED")).isZero();
    }

    @Test
    void a_suggestion_decided_while_a_confirmation_runs_stops_the_confirmation_and_leaves_nothing() {
        UUID order = order("A-1042", "Ana Kamga", PHONE, 5000);
        PaymentIngestionResult payment = f.payments.ingest(tenant, seller, id(), f.forwarded(5000, "Ana Kamga", "1234"));
        UUID suggestion = f.repository.forPayment(tenant, payment.paymentId()).get(0).id();
        AtomicReference<UUID> toReject = new AtomicReference<>(suggestion);
        // Between reading the suggestion and confirming it, another request (another connection) retires it.
        MatchingFixture hooked = new MatchingFixture(MatchConfig.defaults(), (step, c) -> {
            if (step == MatchingService.ApplyStep.MATCH_POSTED) {
                w.run("UPDATE match SET state = 'REJECTED', decided_at = now() WHERE id = ?", toReject.get());
            }
        }, false);
        assertThatThrownBy(() -> hooked.matcher.apply(tenant, order, payment.paymentId(), MatchingService.Mode.CONFIRM, seller))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("changed 0 rows");
        assertThat(MatchingFixture.status("payment", payment.paymentId())).isEqualTo("UNVERIFIED");
        assertThat(MatchingFixture.status("orders", order)).isEqualTo("PENDING_PAYMENT");
        assertThat(MatchingFixture.ledger(tenant)).as("no payment posting").containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000");
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action LIKE 'MATCH_%'", tenant, null)).isZero();
        assertThat(matches("CONFIRMED")).isZero();
        assertThat(matches("REJECTED")).as("the other request's decision stands").isEqualTo(1);
    }
}
