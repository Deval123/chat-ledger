package dev.chatledger.demo.ops;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dev.chatledger.demo.ops.DemoWorld.owner;
import static dev.chatledger.demo.ops.DemoWorld.ownerOne;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fresh demo workspace after its seed (tasks.md, task 9): every matching flow has its outcome, each required flag exists (R1, R5, R8,
 * R10, R11, and R2 and R7 on the way), the orders took their totals from the demo catalog, every payment was accepted, the books balance,
 * and seeding again adds nothing. All of it was written through the ingestion services as the workspace's seller.
 *
 * <p>Requirement 7. Task 9.
 */
class DemoSeedIT {

    private final DemoWorld.Workspace w = DemoWorld.seeded();

    @org.junit.jupiter.api.AfterEach
    void retire() {
        DemoWorld.retire(w);
    }

    private String id(String key) {
        return "seed:" + w.tenant() + ":" + key;
    }

    private String orderStatus(String key) {
        return ownerOne("SELECT status FROM orders WHERE tenant_id = ? AND external_id = ?", w.tenant(), id(key));
    }

    private String paymentStatus(String key) {
        return ownerOne("SELECT status FROM payment WHERE tenant_id = ? AND external_id = ?", w.tenant(), id(key));
    }

    private String total(String key) {
        return ownerOne("SELECT total_minor::text FROM orders WHERE tenant_id = ? AND external_id = ?", w.tenant(), id(key));
    }

    /** The state of the match between an order and a payment of the seed, or null. */
    private String match(String orderKey, String paymentKey) {
        return ownerOne("SELECT m.state FROM match m JOIN orders o ON o.id = m.order_id JOIN payment p ON p.id = m.payment_id "
                + "WHERE m.tenant_id = ? AND o.external_id = ? AND p.external_id = ? ORDER BY m.created_at DESC LIMIT 1", w.tenant(), id(orderKey), id(paymentKey));
    }

    /** Every flag of the workspace as {@code CODE subject-key [order-key]}, sorted. */
    private List<String> flags() {
        return owner("SELECT f.code || ' ' || COALESCE(split_part(p.external_id, ':', 3), '-') || ' ' || COALESCE(split_part(o.external_id, ':', 3), '-') "
                + "FROM flag f LEFT JOIN payment p ON p.id = f.payment_id LEFT JOIN orders o ON o.id = f.order_id WHERE f.tenant_id = ? ORDER BY 1",
                w.tenant());
    }

    @Test
    void every_flow_has_its_outcome() {
        assertThat(orderStatus("auto-order")).isEqualTo("PAID");
        assertThat(match("auto-order", "auto-payment")).isEqualTo("AUTO");
        assertThat(paymentStatus("repeat-payment")).isEqualTo("DUPLICATE");
        assertThat(match("suggested-order", "suggested-payment")).as("no reference: a suggestion").isEqualTo("SUGGESTED");
        assertThat(orderStatus("suggested-order")).isEqualTo("PENDING_PAYMENT");
        assertThat(match("partial-order", "partial-payment")).as("confirmed by the seller").isEqualTo("CONFIRMED");
        assertThat(orderStatus("partial-order")).isEqualTo("PARTIALLY_PAID");
        assertThat(match("over-order", "over-payment")).isEqualTo("SUGGESTED");
        assertThat(paymentStatus("unverified-payment")).isEqualTo("UNVERIFIED");
        assertThat(match("unverified-order", "unverified-payment")).as("never automatic").isEqualTo("SUGGESTED");
        assertThat(orderStatus("unread-order")).isEqualTo("UNPARSED");
        assertThat(match("early-order", "early-payment")).isEqualTo("SUGGESTED");
        assertThat(match("stranger-order", "stranger-payment")).isEqualTo("SUGGESTED");
        assertThat(ownerOne("SELECT count(*)::text FROM match WHERE tenant_id = ? AND state = 'AUTO'", w.tenant())).as("only the exact case").isEqualTo("1");
        assertThat(ownerOne("SELECT count(*)::text FROM payment WHERE tenant_id = ? AND status = 'UNPARSED'", w.tenant())).as("every payment was read").isEqualTo("0");
    }

    @Test
    void each_required_flag_exists_on_its_record() {
        assertThat(flags()).containsExactlyInAnyOrder(
                "DUPLICATE_TX repeat-payment -",                         // R1
                "NO_PROVIDER_ID unverified-payment -",                   // R2
                "OVERPAYMENT over-payment over-order",                   // R11
                "PAID_BEFORE_ORDER early-payment early-order",           // R5
                "NAME_MISMATCH stranger-payment stranger-order",         // R8
                "PHONE_MISMATCH stranger-payment stranger-order",        // R7
                "SENDER_ALIAS - alias-second-order");                    // R10
    }

    @Test
    void the_orders_took_their_totals_from_the_demo_catalog() {
        assertThat(ownerOne("SELECT count(*)::text FROM catalog_item WHERE tenant_id = ?", w.tenant())).isEqualTo("16");
        assertThat(total("auto-order")).isEqualTo("5000");          // 2 x rice 5kg
        assertThat(total("suggested-order")).isEqualTo("6000");     // 2 x tomato box, 1 x plantain bunch
        assertThat(total("partial-order")).isEqualTo("5000");       // 2 x riz 5kg (the French row)
        assertThat(total("unverified-order")).isEqualTo("2800");
        assertThat(total("early-order")).isEqualTo("3600");
        assertThat(total("stranger-order")).isEqualTo("3200");
        assertThat(total("alias-second-order")).isEqualTo("1100");
        assertThat(ownerOne("SELECT count(*)::text FROM orders WHERE tenant_id = ? AND status = 'NEEDS_REVIEW'", w.tenant())).isEqualTo("0");
        assertThat(ownerOne("SELECT count(*)::text FROM order_line WHERE tenant_id = ? AND unit_minor IS NULL", w.tenant())).isEqualTo("0");
    }

    @Test
    void the_books_balance_and_the_seed_was_recorded_by_the_workspace_seller() {
        assertThat(ownerOne("SELECT COALESCE(SUM(e.debit_minor) - SUM(e.credit_minor), 0)::text FROM ledger_entry e "
                + "JOIN ledger_txn t ON t.id = e.txn_id WHERE t.tenant_id = ?", w.tenant())).isEqualTo("0");
        assertThat(owner("SELECT DISTINCT actor::text FROM audit_log WHERE tenant_id = ? AND action IN ('ORDER_INGESTED', 'PAYMENT_INGESTED')",
                w.tenant())).containsExactly(w.seller().toString());
        assertThat(ownerOne("SELECT state FROM demo_tenant WHERE tenant_id = ?", w.tenant())).isEqualTo("READY");
        assertThat(ownerOne("SELECT kind || ' ' || timezone || ' ' || (slug LIKE 'demo-%')::text FROM tenant WHERE id = ?", w.tenant()))
                .isEqualTo("DEMO Africa/Douala true");
    }

    @Test
    void seeding_again_adds_nothing() {
        String before = fingerprint(w.tenant());
        DemoWorld.services().seeder().seed(w.tenant(), w.seller());
        assertThat(fingerprint(w.tenant())).isEqualTo(before);
    }

    static String fingerprint(UUID tenant) {
        return ownerOne("SELECT (SELECT count(*) FROM orders WHERE tenant_id = ?) || '/' || (SELECT count(*) FROM payment WHERE tenant_id = ?) || '/' "
                + "|| (SELECT count(*) FROM match WHERE tenant_id = ?) || '/' || (SELECT count(*) FROM flag WHERE tenant_id = ?) || '/' "
                + "|| (SELECT count(*) FROM ledger_txn WHERE tenant_id = ?) || '/' || (SELECT count(*) FROM audit_log WHERE tenant_id = ?)",
                tenant, tenant, tenant, tenant, tenant, tenant);
    }
}
