package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A decision taken on a payment's page updates that page's status line, as on an order's page (OrderPageDecisionIT): after Confirm or
 * Reject through htmx, the answer carries the payment's status, read again from the database, as an out-of-band swap of its text, and the
 * page's one live region (#notice) says the new status. Without htmx the same decision leads back to the payment's page. Only "order" and
 * "payment" are accepted as an origin, before any decision. All names and numbers are synthetic.
 *
 * <p>Requirements: 6.1, 6.2. Task 8 follow-up (interface only).
 */
class PaymentPageDecisionIT extends BoardIT {

    private static final String PHONE = "+999 000 000 4321";

    /** An order and a payment a helper recorded for it, so the engine only suggests the pair; the payment's id. */
    private UUID suggestedPayment(Workspace w, Browser seller, Browser helper, String ref) {
        pasteOrder(seller, orderText(ref, "Bea Nkomo", PHONE, 4000));
        pastePayment(helper, sms(4000, "Bea Nkomo", PHONE, ref));
        return UUID.fromString(one("SELECT p.id::text FROM payment p JOIN match m ON m.payment_id = p.id JOIN orders o ON o.id = m.order_id "
                + "WHERE p.tenant_id = ? AND o.order_ref = ? AND m.state = 'SUGGESTED'", w.tenantId(), ref));
    }

    private Browser helperOf(Workspace w) {
        Member helper = addUser(w, Role.HELPER);
        return signedIn(w.slug(), helper.email(), helper.password());
    }

    @Test
    void confirm_on_the_payment_page_updates_its_status_and_the_notice_says_it() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        UUID payment = suggestedPayment(w, seller, helperOf(w), "Q-1");

        String page = seller.get("/app/payments/" + payment).body();
        assertThat(page).as("the status line has a stable id").contains("<p class=\"status\" id=\"payment-status\">Received, not matched yet</p>");
        assertThat(page.split("role=\"status\"", -1)).as("one live region on the page, #notice (A11yRules)").hasSize(2);
        assertThat(page).as("the payment page's forms say where they come from").contains("name=\"from\" value=\"payment\"")
                .doesNotContain("name=\"from\" value=\"order\"");
        String confirm = find("action=\"(/app/matches/[0-9a-f-]{36}/confirm)\"", page);

        Browser.Response answer = htmx(seller, confirm, Map.of("from", "payment"));

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).as("the live region says the result and the new status")
                .contains("<p id=\"notice\" hx-swap-oob=\"innerHTML\"><span>Confirmed and applied to the order.</span> <span>Payment status: Matched.</span></p>")
                .contains("<p class=\"status\" id=\"payment-status\" hx-swap-oob=\"innerHTML\">Matched</p>")
                .doesNotContain("order-status").doesNotContain("<html");
        assertThat(one("SELECT status FROM payment WHERE id = ?", payment)).isEqualTo("MATCHED");
        assertThat(seller.get("/app/payments/" + payment).body()).as("the page after a reload says the same")
                .contains("<p class=\"status\" id=\"payment-status\">Matched</p>");
    }

    @Test
    void reject_on_the_payment_page_sends_the_status_again_and_it_is_still_free() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        UUID payment = suggestedPayment(w, seller, helperOf(w), "Q-2");
        String reject = find("action=\"(/app/matches/[0-9a-f-]{36}/reject)\"", seller.get("/app/payments/" + payment).body());

        Browser.Response answer = htmx(seller, reject, Map.of("from", "payment"));

        assertThat(answer.body()).contains("<span>Suggestion rejected.</span> <span>Payment status: Received, not matched yet.</span>")
                .contains("<p class=\"status\" id=\"payment-status\" hx-swap-oob=\"innerHTML\">Received, not matched yet</p>");
    }

    @Test
    void without_htmx_a_decision_on_the_payment_page_leads_back_to_that_page() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        UUID payment = suggestedPayment(w, seller, helperOf(w), "Q-3");
        String confirm = find("action=\"(/app/matches/[0-9a-f-]{36}/confirm)\"", seller.get("/app/payments/" + payment).body());

        Browser.Response answer = post(seller, confirm, Map.of("from", "payment"));

        assertThat(path(answer)).isEqualTo("/app/payments/" + payment + "?n=MATCH_CONFIRMED");
        assertThat(seller.get(path(answer)).body()).contains("Confirmed and applied to the order.").contains("id=\"payment-status\">Matched</p>");
    }

    @Test
    void an_unknown_origin_is_refused_before_any_decision() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        UUID payment = suggestedPayment(w, seller, helperOf(w), "Q-4");
        String confirm = find("action=\"(/app/matches/[0-9a-f-]{36}/confirm)\"", seller.get("/app/payments/" + payment).body());

        for (String from : new String[]{"board", "Payment", "payment ", ""}) {
            assertThat(htmx(seller, confirm, Map.of("from", from)).status()).as("from=[" + from + "]").isEqualTo(400);
        }
        assertThat(one("SELECT status FROM payment WHERE id = ?", payment)).as("a refused origin decides nothing").isEqualTo("RECEIVED");
    }
}
