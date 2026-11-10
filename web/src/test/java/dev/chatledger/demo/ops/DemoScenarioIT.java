package dev.chatledger.demo.ops;

import dev.chatledger.board.Notice;
import dev.chatledger.demo.Scenario;
import dev.chatledger.demo.Script;
import dev.chatledger.demo.web.DemoScenarios;
import dev.chatledger.ingestion.RejectReason;
import dev.chatledger.matching.MatchingService;
import dev.chatledger.orders.IngestionResult;
import dev.chatledger.payments.PaymentIngestionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.chatledger.demo.ops.DemoWorld.owner;
import static dev.chatledger.demo.ops.DemoWorld.ownerOne;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seven one-click scenarios of requirement 7.2, each run through {@link DemoScenarios} (the same ingestion services as a pasted
 * message, as the workspace's seller) on a seeded demo workspace, with its outcome asserted on the database: statuses, match states, flag
 * rows and postings. Also the two input rules only a demo workspace has: made-up phones only, and the row cap.
 *
 * <p>Requirement 7. Task 9 (design stop, section 3).
 */
class DemoScenarioIT {

    private final DemoWorld.Workspace w = DemoWorld.seeded();
    private final DemoCommand.Services services = DemoWorld.services();
    private final DemoScenarios scenarios = new DemoScenarios(services.orders(), services.payments(), services.messages());
    private final Map<Scenario, Script> store = new EnumMap<>(Scenario.class);

    @AfterEach
    void retire() {
        DemoWorld.retire(w);
    }

    private UUID order(String reference) {
        return UUID.fromString(ownerOne("SELECT id::text FROM orders WHERE tenant_id = ? AND order_ref = ?", w.tenant(), reference));
    }

    private List<String> payments(String reference) {
        return owner("SELECT id::text FROM payment WHERE tenant_id = ? AND reference = ? ORDER BY created_at, id", w.tenant(), reference);
    }

    private String status(String table, Object id) {
        return ownerOne("SELECT status FROM " + table + " WHERE tenant_id = ? AND id = ?::uuid", w.tenant(), id.toString());
    }

    private String matchState(UUID order, String payment) {
        return ownerOne("SELECT state FROM match WHERE tenant_id = ? AND order_id = ?::uuid AND payment_id = ?::uuid ORDER BY created_at DESC LIMIT 1",
                w.tenant(), order.toString(), payment);
    }

    private List<String> flags(String payment, UUID order) {
        return owner("SELECT code FROM flag WHERE tenant_id = ? AND (payment_id = ?::uuid OR (subject_type = 'ORDER' AND order_id = ?::uuid)) "
                + "AND (order_id IS NULL OR order_id = ?::uuid) ORDER BY code", w.tenant(), payment, order.toString(), order.toString());
    }

    /** The scenario's script, made by the same generator the service uses, so its reference is known before it is sent. */
    private Script run(Scenario s) {
        scenarios.run(w.tenant(), w.seller(), s, DemoScenarios.Step.ORDER, store);
        Script script = store.get(s);
        DemoScenarios.Outcome sent = scenarios.run(w.tenant(), w.seller(), s, DemoScenarios.Step.PAYMENT, store);
        assertThat(sent.notice()).as(s.name()).isEqualTo(Notice.DEMO_PAYMENT_SENT);
        assertThat(store).as("the script is used once").doesNotContainKey(s);
        return script;
    }

    private UUID suggestionId(UUID order, String payment) {
        return UUID.fromString(ownerOne("SELECT id::text FROM match WHERE tenant_id = ? AND order_id = ?::uuid AND payment_id = ?::uuid AND state = 'SUGGESTED'",
                w.tenant(), order.toString(), payment));
    }

    @Test
    void one_happy_path_is_matched_by_itself_with_no_flag() {
        Script s = run(Scenario.HAPPY_PATH);
        UUID order = order(s.reference());
        String payment = payments(s.reference()).get(0);
        assertThat(matchState(order, payment)).isEqualTo("AUTO");
        assertThat(status("orders", order)).isEqualTo("PAID");
        assertThat(flags(payment, order)).isEmpty();
        assertThat(ownerOne("SELECT count(*)::text FROM ledger_txn WHERE tenant_id = ? AND kind = 'PAYMENT_MATCHED' AND ref_id = (SELECT id FROM match "
                + "WHERE order_id = ?::uuid AND state = 'AUTO')", w.tenant(), order.toString())).isEqualTo("1");
    }

    @Test
    void two_an_underpayment_is_a_suggestion_and_confirmed_leaves_the_rest_owed() {
        Script s = run(Scenario.UNDERPAYMENT);
        UUID order = order(s.reference());
        String payment = payments(s.reference()).get(0);
        assertThat(matchState(order, payment)).isEqualTo("SUGGESTED");
        assertThat(flags(payment, order)).isEmpty();
        MatchingService.ApplyResult confirmed = services.matcher().confirm(w.tenant(), suggestionId(order, payment), w.seller());
        assertThat(confirmed.appliedMinor()).isEqualTo(3000);
        assertThat(status("orders", order)).isEqualTo("PARTIALLY_PAID");
    }

    @Test
    void three_an_overpayment_is_flagged_and_confirmed_puts_the_excess_in_customer_credit() {
        Script s = run(Scenario.OVERPAYMENT);
        UUID order = order(s.reference());
        String payment = payments(s.reference()).get(0);
        assertThat(flags(payment, order)).containsExactly("OVERPAYMENT");
        assertThat(matchState(order, payment)).isEqualTo("SUGGESTED");
        MatchingService.ApplyResult confirmed = services.matcher().confirm(w.tenant(), suggestionId(order, payment), w.seller());
        assertThat(confirmed.appliedMinor()).isEqualTo(5000);
        assertThat(confirmed.excessMinor()).isEqualTo(600);
        assertThat(status("orders", order)).isEqualTo("PAID");
    }

    @Test
    void four_a_repeated_confirmation_is_flagged_and_never_posted() {
        Script s = run(Scenario.DUPLICATE);
        UUID order = order(s.reference());
        List<String> both = payments(s.reference());
        assertThat(both).hasSize(2);
        assertThat(matchState(order, both.get(0))).isEqualTo("AUTO");
        assertThat(status("payment", both.get(1))).isEqualTo("DUPLICATE");
        assertThat(owner("SELECT code FROM flag WHERE tenant_id = ? AND payment_id = ?::uuid", w.tenant(), both.get(1))).containsExactly("DUPLICATE_TX");
        assertThat(ownerOne("SELECT count(*)::text FROM match WHERE tenant_id = ? AND payment_id = ?::uuid", w.tenant(), both.get(1))).isEqualTo("0");
    }

    @Test
    void five_a_screenshot_without_a_transaction_id_is_unverified_and_never_automatic() {
        Script s = run(Scenario.SCREENSHOT);
        UUID order = order(s.reference());
        String payment = ownerOne("SELECT id::text FROM payment WHERE tenant_id = ? AND external_id LIKE 'sim:%' AND status = 'UNVERIFIED' "
                + "ORDER BY created_at DESC LIMIT 1", w.tenant());
        assertThat(flags(payment, order)).containsExactly("NO_PROVIDER_ID");
        assertThat(matchState(order, payment)).isEqualTo("SUGGESTED");
        assertThat(ownerOne("SELECT score::text FROM match WHERE tenant_id = ? AND payment_id = ?::uuid", w.tenant(), payment)).isEqualTo("75");
    }

    @Test
    void six_a_sender_unlike_the_customer_is_flagged_twice() {
        Script s = run(Scenario.WRONG_SENDER);
        UUID order = order(s.reference());
        String payment = payments(s.reference()).get(0);
        assertThat(flags(payment, order)).containsExactly("NAME_MISMATCH", "PHONE_MISMATCH");
        assertThat(matchState(order, payment)).isEqualTo("SUGGESTED");
    }

    @Test
    void seven_an_old_confirmation_for_a_new_order_is_flagged_for_both_times() {
        Script s = run(Scenario.LATE_PAYMENT);
        UUID order = order(s.reference());
        String payment = payments(s.reference()).get(0);
        assertThat(flags(payment, order)).containsExactly("PAID_BEFORE_ORDER", "PAID_OUTSIDE_USUAL_WINDOW");
        assertThat(matchState(order, payment)).isEqualTo("SUGGESTED");
        assertThat(status("orders", order)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void run_both_does_what_order_then_payment_does_and_a_payment_needs_its_order_first() {
        int before = owner("SELECT id FROM payment WHERE tenant_id = ?", w.tenant()).size();
        DemoScenarios.Outcome refused = scenarios.run(w.tenant(), w.seller(), Scenario.HAPPY_PATH, DemoScenarios.Step.PAYMENT, store);
        assertThat(refused.notice()).isEqualTo(Notice.DEMO_SEND_ORDER_FIRST);
        assertThat(refused.toBoard()).isFalse();
        assertThat(owner("SELECT id FROM payment WHERE tenant_id = ?", w.tenant())).hasSize(before);
        DemoScenarios.Outcome order = scenarios.run(w.tenant(), w.seller(), Scenario.HAPPY_PATH, DemoScenarios.Step.ORDER, store);
        assertThat(order.notice()).isEqualTo(Notice.DEMO_ORDER_SENT);
        assertThat(order.toBoard()).isFalse();
        assertThat(store).containsKey(Scenario.HAPPY_PATH);
        DemoScenarios.Outcome bothAtOnce = scenarios.run(w.tenant(), w.seller(), Scenario.UNDERPAYMENT, DemoScenarios.Step.BOTH, store);
        assertThat(bothAtOnce.notice()).isEqualTo(Notice.DEMO_PAYMENT_SENT);
        assertThat(owner("SELECT id FROM payment WHERE tenant_id = ?", w.tenant())).hasSize(before + 1);
        assertThat(owner("SELECT DISTINCT split_part(external_id, ':', 1) FROM payment WHERE tenant_id = ? AND external_id NOT LIKE 'seed:%'", w.tenant()))
                .containsExactly("sim");
    }

    // ── The demo's own input rules (D11, D12) ───────────────────────────────

    @Test
    void a_demo_workspace_takes_made_up_phones_only_and_a_regular_one_is_not_affected() {
        String realOrder = "Order: X-1\nName: Ana Testa\nPhone: +237 600 000 001\n2 x rice 5kg";
        IngestionResult o = services.orders().ingest(w.tenant(), w.seller(), "test:" + UUID.randomUUID(), realOrder);
        assertThat(o.outcome()).isEqualTo(IngestionResult.Outcome.REJECTED_INPUT);
        assertThat(o.rejectReason()).isEqualTo(RejectReason.REAL_PHONE_IN_DEMO);
        String paidAt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .format(java.time.Instant.now().minusSeconds(60).atOffset(java.time.ZoneOffset.ofHours(1)));
        String realPayment = "You received 5000 XAF from Ana Testa (+237 600 000 001). Txn DTABCDEFGH. " + paidAt;
        PaymentIngestionResult p = services.payments().ingest(w.tenant(), w.seller(), "test:" + UUID.randomUUID(), realPayment);
        assertThat(p.outcome()).isEqualTo(PaymentIngestionResult.Outcome.REJECTED_INPUT);
        assertThat(p.rejectReason()).isEqualTo(RejectReason.REAL_PHONE_IN_DEMO);
        assertThat(ownerOne("SELECT count(*)::text FROM orders WHERE tenant_id = ? AND order_ref = 'X-1'", w.tenant())).isEqualTo("0");
        // A forward with the last four digits only has no number to check, and a synthetic one passes.
        assertThat(services.orders().ingest(w.tenant(), w.seller(), "test:" + UUID.randomUUID(), realOrder.replace("+237 600 000 001", "+999 000 000 1999"))
                .outcome()).isEqualTo(IngestionResult.Outcome.CREATED);
    }

    @Test
    void a_demo_workspace_holds_at_most_three_hundred_orders_and_payments_and_says_so() {
        int now = Integer.parseInt(ownerOne("SELECT ((SELECT count(*) FROM orders WHERE tenant_id = ?) + (SELECT count(*) FROM payment WHERE tenant_id = ?))::text",
                w.tenant(), w.tenant()));
        // Filled up to one short of the cap as the owner, then the last one through the service.
        DemoWorld.column(DemoWorld.DB.migrateDataSource(), "INSERT INTO orders(id, tenant_id, external_id, total_minor, currency, status, raw_text) "
                + "SELECT gen_random_uuid(), ?, 'fill-' || g, NULL, 'XAF', 'UNPARSED', 'filler' FROM generate_series(1, ?) g", w.tenant(), 299 - now);
        String text = "Order: F-1\nName: Ana Testa\nPhone: +999 000 000 1001\n1 x soap bar";
        assertThat(services.orders().ingest(w.tenant(), w.seller(), "test:last", text).outcome()).isEqualTo(IngestionResult.Outcome.CREATED);
        IngestionResult full = services.orders().ingest(w.tenant(), w.seller(), "test:one-more", text.replace("F-1", "F-2"));
        assertThat(full.outcome()).isEqualTo(IngestionResult.Outcome.REJECTED_INPUT);
        assertThat(full.rejectReason()).isEqualTo(RejectReason.DEMO_WORKSPACE_FULL);
        assertThat(services.payments().ingest(w.tenant(), w.seller(), "test:pay", "anything").rejectReason()).isEqualTo(RejectReason.DEMO_WORKSPACE_FULL);
        assertThat(Notice.rejected(RejectReason.DEMO_WORKSPACE_FULL, Notice.ORDER_INPUT_REJECTED)).isEqualTo(Notice.DEMO_FULL);
        DemoScenarios.Outcome refused = scenarios.run(w.tenant(), w.seller(), Scenario.HAPPY_PATH, DemoScenarios.Step.ORDER, store);
        assertThat(refused.notice()).as("a scenario says so too").isEqualTo(Notice.DEMO_FULL);
        assertThat(refused.toBoard()).isFalse();
        assertThat(store).as("nothing waits for a payment").isEmpty();
        // A seed that is refused stops (a fresh workspace, already full: its first seed message is new and refused).
        DemoWorld.Workspace fresh = DemoWorld.created();
        DemoWorld.column(DemoWorld.DB.migrateDataSource(), "INSERT INTO orders(id, tenant_id, external_id, total_minor, currency, status, raw_text) "
                + "SELECT gen_random_uuid(), ?, 'fill-' || g, NULL, 'XAF', 'UNPARSED', 'filler' FROM generate_series(1, 300) g", fresh.tenant());
        assertThatThrownBy(() -> services.seeder().seed(fresh.tenant(), fresh.seller())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the seed's order auto-order was refused");
        DemoWorld.retire(fresh);
        // The database refuses it too, for the owner as well: the application's check is not the only one.
        assertThatThrownBy(() -> DemoWorld.column(DemoWorld.DB.migrateDataSource(), "INSERT INTO payment(id, tenant_id, external_id, currency, status, raw_text) "
                + "VALUES (gen_random_uuid(), ?, 'fill-pay', 'XAF', 'UNPARSED', 'filler')", w.tenant())).hasMessageContaining("this demo workspace is full");
        assertThat(ownerOne("SELECT ((SELECT count(*) FROM orders WHERE tenant_id = ?) + (SELECT count(*) FROM payment WHERE tenant_id = ?))::text",
                w.tenant(), w.tenant())).isEqualTo("300");
    }
}
