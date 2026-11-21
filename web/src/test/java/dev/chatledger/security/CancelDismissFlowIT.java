package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cancel and dismiss as a seller uses them (task 12b): from the record's page, through the check page that writes nothing, to the post;
 * then the board, the summary and the books agree. A helper sees neither action.
 */
class CancelDismissFlowIT extends BoardIT {

    private static final String PHONE = "+999 000 000 1234";

    @Test
    void the_seller_cancels_an_order_from_its_page_and_it_leaves_the_board_and_the_books() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        pasteOrder(b, orderText("K-1", "Ana Kamga", PHONE, 5000));
        UUID order = orderId(w.tenantId(), "K-1");
        assertThat(b.get("/app").body()).contains("Awaiting 1");
        String page = b.get("/app/orders/" + order).body();
        assertThat(page).contains("/app/orders/" + order + "/cancel").contains("Cancel this order")
                .contains("Still to pay").as("an open order shows what is owed").contains("5,000 XAF");

        Browser.Response check = b.get("/app/orders/" + order + "/cancel");
        assertThat(check.status()).isEqualTo(200);
        assertThat(check.body()).contains("Cancel this order?").contains("5,000 XAF").contains("This cannot be undone.");
        assertThat(one("SELECT status FROM orders WHERE id = ?", order)).as("the check page writes nothing").isEqualTo("PENDING_PAYMENT");

        Browser.Response done = post(b, "/app/orders/" + order + "/cancel", Map.of());
        assertThat(path(done)).isEqualTo("/app?n=ORDER_CANCELLED&r=" + order);
        assertThat(b.get(path(done)).body()).contains("Order cancelled. Its receivable was reversed in the books.").contains("Awaiting 0")
                .doesNotContain(">Ana Kamga<");
        assertThat(one("SELECT status FROM orders WHERE id = ?", order)).isEqualTo("CANCELLED");
        String after = b.get("/app/orders/" + order).body();
        assertThat(after).contains("Cancelled").doesNotContain("/app/orders/" + order + "/cancel")
                .as("a cancelled order owes nothing, so no balance is shown (register 180)").doesNotContain("Still to pay");
        assertThat(b.get("/app/summary").body()).contains("0 orders recorded");
        String books = b.get("/app/books").body();
        assertThat(books).contains("The books balance.").doesNotContain("DOES NOT AGREE");

        // Once cancelled, the check page and the post both say so and change nothing.
        assertThat(path(b.get("/app/orders/" + order + "/cancel"))).isEqualTo("/app/orders/" + order + "?n=CANCEL_ALREADY");
        assertThat(path(post(b, "/app/orders/" + order + "/cancel", Map.of()))).isEqualTo("/app?n=CANCEL_ALREADY");
        assertThat(one("SELECT count(*)::text FROM audit_log WHERE tenant_id = ? AND action = 'ORDER_CANCELLED'", w.tenantId())).isEqualTo("1");
    }

    @Test
    void an_order_with_a_payment_cannot_be_cancelled_and_the_page_says_to_undo_the_match_first() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        pasteOrder(b, orderText("K-2", "Ana Kamga", PHONE, 5000));
        pastePayment(b, sms(5000, "Ana Kamga", PHONE, "K-2"));
        UUID order = orderId(w.tenantId(), "K-2");
        assertThat(one("SELECT status FROM orders WHERE id = ?", order)).isEqualTo("PAID");
        assertThat(b.get("/app/orders/" + order).body()).doesNotContain("/app/orders/" + order + "/cancel");
        assertThat(path(b.get("/app/orders/" + order + "/cancel"))).isEqualTo("/app/orders/" + order + "?n=CANCEL_HAS_PAYMENT");
        Browser.Response refused = post(b, "/app/orders/" + order + "/cancel", Map.of());
        assertThat(b.get(path(refused)).body()).contains("This order has a payment matched to it. Undo that match first.");
        assertThat(one("SELECT status FROM orders WHERE id = ?", order)).isEqualTo("PAID");
    }

    @Test
    void the_seller_dismisses_an_unreadable_message_and_it_leaves_the_board() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        pasteOrder(b, "hello, is the shop open?");
        UUID order = UUID.fromString(one("SELECT id::text FROM orders WHERE tenant_id = ? AND status = 'UNPARSED'", w.tenantId()));
        assertThat(b.get("/app").body()).contains("Unparsed 1");
        assertThat(b.get("/app/orders/" + order).body()).contains("/app/orders/" + order + "/dismiss");
        Browser.Response check = b.get("/app/orders/" + order + "/dismiss");
        assertThat(check.status()).isEqualTo(200);
        assertThat(check.body()).contains("Dismiss this order?").contains("Nothing was posted for it");

        Browser.Response done = post(b, "/app/orders/" + order + "/dismiss", Map.of());
        assertThat(path(done)).isEqualTo("/app?n=ORDER_DISMISSED&r=" + order);
        assertThat(b.get("/app").body()).contains("Unparsed 0");
        String page = b.get("/app/orders/" + order).body();
        assertThat(page).contains("Dismissed by the seller on").doesNotContain("/app/orders/" + order + "/dismiss");
        assertThat(path(post(b, "/app/orders/" + order + "/dismiss", Map.of()))).isEqualTo("/app?n=DISMISS_ALREADY");
    }

    @Test
    void a_helper_sees_neither_action() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        Browser h = signedIn(w.slug(), helper.email(), helper.password());
        pasteOrder(seller, orderText("K-3", "Ana Kamga", PHONE, 5000));
        pasteOrder(seller, "hello again");
        UUID order = orderId(w.tenantId(), "K-3");
        UUID unparsed = UUID.fromString(one("SELECT id::text FROM orders WHERE tenant_id = ? AND status = 'UNPARSED'", w.tenantId()));
        assertThat(h.get("/app/orders/" + order).body()).doesNotContain("/cancel");
        assertThat(h.get("/app/orders/" + unparsed).body()).doesNotContain("/dismiss");
        assertThat(post(h, "/app/orders/" + order + "/cancel", Map.of()).status()).isEqualTo(403);
        assertThat(one("SELECT status FROM orders WHERE id = ?", order)).isEqualTo("PENDING_PAYMENT");
    }
}
