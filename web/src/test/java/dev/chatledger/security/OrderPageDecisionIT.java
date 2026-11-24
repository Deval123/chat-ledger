package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A decision taken on an order's page updates that page's header: after Confirm or Reject through htmx, the answer carries the order's
 * status line and its "Still to pay" amount, read again from the database, as out-of-band swaps of their inner text. The status line
 * is not replaced, and the page's one live region, #notice, says the new status. Without htmx the same decision leads back to the order's page.
 * Undo goes through its check page and a plain post, which leads to a freshly rendered page, so it cannot leave a stale header.
 * All names and numbers are synthetic.
 *
 * <p>Requirements: 6.1, 6.2. Task 12a follow-up (interface only).
 */
class OrderPageDecisionIT extends BoardIT {

    private static final String PHONE = "+999 000 000 4321";

    /** An order and a payment a helper recorded for it, so the engine only suggests the pair. */
    private UUID suggestedOrder(Workspace w, Browser seller, Browser helper, String ref) {
        pasteOrder(seller, orderText(ref, "Bea Nkomo", PHONE, 4000));
        pastePayment(helper, sms(4000, "Bea Nkomo", PHONE, ref));
        return orderId(w.tenantId(), ref);
    }

    @Test
    void confirm_on_the_order_page_updates_its_status_and_what_is_still_to_pay() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        UUID order = suggestedOrder(w, seller, signedIn(w.slug(), helper.email(), helper.password()), "P-1");

        String page = seller.get("/app/orders/" + order).body();
        assertThat(page).as("the status line and the balance have stable ids")
                .containsPattern("<p class=\"status\" id=\"order-status\">Waiting for payment</p>")
                .contains("<span id=\"order-balance\">4,000 XAF</span>");
        assertThat(page.split("role=\"status\"", -1)).as("one live region on the page, #notice (A11yRules)").hasSize(2);
        assertThat(page).contains("<p id=\"notice\" role=\"status\"");
        String confirm = find("action=\"(/app/matches/[0-9a-f-]{36}/confirm)\"", page);
        assertThat(page).as("the order page's forms say where they come from").contains("name=\"from\" value=\"order\"");

        Browser.Response answer = htmx(seller, confirm, Map.of("from", "order"));

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).as("the live region says the result and the new status")
                .contains("<p id=\"notice\" hx-swap-oob=\"innerHTML\"><span>Confirmed and applied to the order.</span> <span>Order status: Paid.</span></p>")
                .contains("<p class=\"status\" id=\"order-status\" hx-swap-oob=\"innerHTML\">Paid</p>")
                .contains("<span id=\"order-balance\" hx-swap-oob=\"innerHTML\">0 XAF</span>")
                .doesNotContain("<html");
        assertThat(one("SELECT status FROM orders WHERE id = ?", order)).isEqualTo("PAID");
        assertThat(seller.get("/app/orders/" + order).body()).as("the page after a reload says the same")
                .contains("id=\"order-status\">Paid</p>").contains("<span id=\"order-balance\">0 XAF</span>");
    }

    @Test
    void reject_on_the_order_page_sends_the_header_again_and_it_still_waits() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        UUID order = suggestedOrder(w, seller, signedIn(w.slug(), helper.email(), helper.password()), "P-2");
        String reject = find("action=\"(/app/matches/[0-9a-f-]{36}/reject)\"", seller.get("/app/orders/" + order).body());

        Browser.Response answer = htmx(seller, reject, Map.of("from", "order"));

        assertThat(answer.body()).contains("<span>Suggestion rejected.</span> <span>Order status: Waiting for payment.</span>")
                .contains("<p class=\"status\" id=\"order-status\" hx-swap-oob=\"innerHTML\">Waiting for payment</p>")
                .contains("<span id=\"order-balance\" hx-swap-oob=\"innerHTML\">4,000 XAF</span>");
    }

    @Test
    void without_htmx_a_decision_on_the_order_page_leads_back_to_that_page() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        UUID order = suggestedOrder(w, seller, signedIn(w.slug(), helper.email(), helper.password()), "P-3");
        String confirm = find("action=\"(/app/matches/[0-9a-f-]{36}/confirm)\"", seller.get("/app/orders/" + order).body());

        Browser.Response answer = post(seller, confirm, Map.of("from", "order"));

        assertThat(path(answer)).isEqualTo("/app/orders/" + order + "?n=MATCH_CONFIRMED");
        assertThat(seller.get(path(answer)).body()).contains("Confirmed and applied to the order.").contains("id=\"order-status\">Paid</p>");
    }

    @Test
    void a_decision_from_anywhere_else_is_answered_as_before_and_an_unknown_origin_is_refused() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        UUID order = suggestedOrder(w, seller, signedIn(w.slug(), helper.email(), helper.password()), "P-4");
        String confirm = find("action=\"(/app/matches/[0-9a-f-]{36}/confirm)\"", seller.get("/app/orders/" + order).body());

        assertThat(htmx(seller, confirm, Map.of("from", "board")).status()).as("only the fixed value is accepted").isEqualTo(400);
        assertThat(one("SELECT status FROM orders WHERE id = ?", order)).as("a refused origin decides nothing").isEqualTo("PENDING_PAYMENT");
        Browser.Response board = htmx(seller, confirm, Map.of());
        assertThat(board.body()).contains("Confirmed and applied to the order.").doesNotContain("order-status");
    }

    @Test
    void undo_goes_through_its_check_page_and_a_plain_post_so_the_next_page_is_fresh() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        pasteOrder(seller, orderText("P-5", "Bea Nkomo", PHONE, 4000));
        pastePayment(seller, sms(4000, "Bea Nkomo", PHONE, "P-5"));
        UUID order = orderId(w.tenantId(), "P-5");
        String page = seller.get("/app/orders/" + order).body();
        assertThat(page).contains("id=\"order-status\">Paid</p>");
        String undo = find("href=\"(/app/matches/[0-9a-f-]{36}/undo)\"", page);
        String unmatch = find("action=\"(/app/matches/[0-9a-f-]{36}/unmatch)\"", seller.get(undo).body());
        assertThat(seller.get(undo).body()).as("the check page's form is a plain post, not htmx").doesNotContain("hx-boost");

        Browser.Response answer = post(seller, unmatch, Map.of());

        assertThat(answer.status()).isEqualTo(302);
        assertThat(seller.get("/app/orders/" + order).body()).contains("id=\"order-status\">Waiting for payment</p>")
                .contains("<span id=\"order-balance\">4,000 XAF</span>");
    }
}
