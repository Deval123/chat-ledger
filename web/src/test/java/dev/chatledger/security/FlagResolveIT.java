package dev.chatledger.security;

import dev.chatledger.flags.FlagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seller resolves a flag through the real application: the sentence and the button on the card and on the payment page, one click,
 * the audit row, the record leaving the Flagged column for where its status puts it (task 7, D9), a second click answered in a sentence. A
 * helper sees the sentence and no button, and the endpoint answers 403; another workspace gets 404 and changes nothing.
 *
 * <p>Requirements: 5, 6, 9.1. Task 7.
 */
class FlagResolveIT extends BoardIT {

    @Autowired
    FlagService flags;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private String forwarded(long amount, String name, String last4) {
        return "Forwarded: received " + amount + " XAF from " + name + " (" + last4 + ") on "
                + DATE.format(clock.instant().minus(Duration.ofMinutes(5)).atOffset(ZoneOffset.ofHours(1)));
    }

    private UUID flagOf(UUID tenant, String code) {
        return UUID.fromString(one("SELECT id::text FROM flag WHERE tenant_id = ? AND code = ?", tenant, code));
    }

    @Test
    void the_seller_resolves_a_flag_from_the_card_and_the_payment_moves_back_to_review() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        pastePayment(seller, forwarded(5000, "Ana Kamga", "1234"));
        UUID payment = UUID.fromString(one("SELECT id::text FROM payment WHERE tenant_id = ?", w.tenantId()));
        UUID flag = flagOf(w.tenantId(), "NO_PROVIDER_ID");

        String board = seller.get("/app").body();
        assertThat(board).contains("1 flagged now.");
        assertThat(seller.get("/app/summary").body()).contains("1 items flagged now");
        assertThat(board).contains("Flagged 1").contains("Review 0")
                .contains("This confirmation has no transaction id. Check your provider balance before you hand over the goods.")
                .contains("action=\"/app/flags/" + flag + "/resolve\"").contains(">I checked this<");
        String page = seller.get("/app/payments/" + payment).body();
        assertThat(page).contains("This confirmation has no transaction id.").contains(">Open<").contains("/app/flags/" + flag + "/resolve");

        Browser.Response resolved = post(seller, "/app/flags/" + flag + "/resolve", Map.of());
        assertThat(resolved.status()).isEqualTo(302);
        assertThat(path(resolved)).isEqualTo("/app/payments/" + payment + "?n=FLAG_RESOLVED");
        assertThat(seller.get(path(resolved)).body()).contains("Marked as checked.").contains("Checked by you,").doesNotContain("/app/flags/" + flag + "/resolve");
        assertThat(one("SELECT (resolved_at IS NOT NULL)::text || ' ' || resolved_by::text FROM flag WHERE id = ?", flag)).isEqualTo("true " + w.sellerId());
        assertThat(rows("SELECT action || ' ' || subject || ' ' || actor FROM audit_log WHERE tenant_id = ? AND action = 'FLAG_RESOLVED'", w.tenantId()))
                .containsExactly("FLAG_RESOLVED " + flag + " " + w.sellerId());

        String after = seller.get("/app").body();
        assertThat(after).as("D9: an unverified payment whose flag was checked waits in Review").contains("Flagged 0").contains("Review 1");
        assertThat(after).as("its card no longer offers the checked flag").doesNotContain("/app/flags/" + flag + "/resolve")
                .doesNotContain("This confirmation has no transaction id.").contains("0 flagged now.");
        assertThat(seller.get("/app/summary").body()).contains("0 items flagged now");

        Browser.Response again = post(seller, "/app/flags/" + flag + "/resolve", Map.of());
        assertThat(path(again)).isEqualTo("/app/payments/" + payment + "?n=FLAG_ALREADY_RESOLVED");
        assertThat(seller.get(path(again)).body()).contains("That flag was already marked as checked.");
        assertThat(rows("SELECT subject FROM audit_log WHERE tenant_id = ? AND action = 'FLAG_RESOLVED'", w.tenantId())).hasSize(1);
    }

    @Test
    void a_resolved_repeat_leaves_flagged_for_the_list_of_payments_set_aside_or_repeated_and_htmx_gets_the_card_answer() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        String text = sms(5000, "Ana Kamga", "+999 000 000 1234", null);
        pastePayment(seller, text);
        pastePayment(seller, text);
        UUID flag = flagOf(w.tenantId(), "DUPLICATE_TX");
        assertThat(seller.get("/app").body()).contains("Flagged 1").contains("This transaction id was already recorded.");
        Browser.Response viaHtmx = htmx(seller, "/app/flags/" + flag + "/resolve", Map.of());
        assertThat(viaHtmx.status()).isEqualTo(200);
        assertThat(viaHtmx.body()).contains("Marked as checked.").contains("hx-swap-oob=\"true\"").doesNotContain("<html");
        String board = seller.get("/app").body();
        assertThat(board).contains("Flagged 0").contains("Payments set aside or repeated: 1");
        assertThat(seller.get("/app/columns/aside").body()).contains("Repeat of a payment already recorded; not posted");
    }

    @Test
    void a_helper_sees_the_sentence_but_no_button_and_the_endpoint_refuses() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser helperBrowser = signedIn(w.slug(), helper.email(), helper.password());
        pastePayment(helperBrowser, forwarded(5000, "Ana Kamga", "1234"));
        UUID flag = flagOf(w.tenantId(), "NO_PROVIDER_ID");
        UUID payment = UUID.fromString(one("SELECT id::text FROM payment WHERE tenant_id = ?", w.tenantId()));
        String board = helperBrowser.get("/app").body();
        assertThat(board).contains("This confirmation has no transaction id.").doesNotContain("/resolve").doesNotContain("I checked this");
        assertThat(helperBrowser.get("/app/payments/" + payment).body()).contains("This confirmation has no transaction id.").doesNotContain("/resolve");
        assertThat(post(helperBrowser, "/app/flags/" + flag + "/resolve", Map.of()).status()).isEqualTo(403);
        assertThat(one("SELECT (resolved_at IS NULL)::text FROM flag WHERE id = ?", flag)).isEqualTo("true");
        assertThat(rows("SELECT subject FROM audit_log WHERE tenant_id = ? AND action = 'FLAG_RESOLVED'", w.tenantId())).isEmpty();
    }

    @Test
    void a_seller_of_another_workspace_gets_404_and_changes_nothing() {
        Workspace a = newWorkspace();
        Browser sellerA = signedIn(a.slug(), a.email(), a.password());
        pastePayment(sellerA, forwarded(5000, "Ana Kamga", "1234"));
        UUID flag = flagOf(a.tenantId(), "NO_PROVIDER_ID");
        Workspace b = newWorkspace();
        Browser sellerB = signedIn(b.slug(), b.email(), b.password());
        Browser.Response refused = post(sellerB, "/app/flags/" + flag + "/resolve", Map.of());
        assertThat(refused.status()).isEqualTo(404);
        assertThat(refused.body()).doesNotContain("Ana Kamga").doesNotContain("transaction id");
        assertThat(one("SELECT (resolved_at IS NULL)::text FROM flag WHERE id = ?", flag)).isEqualTo("true");
        assertThat(post(sellerB, "/app/flags/not-a-uuid/resolve", Map.of()).status()).isEqualTo(400);
    }

    @Test
    void a_flagged_suggestion_shows_its_flags_and_the_seller_can_still_confirm_it() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        pasteOrder(seller, orderText("F-1", "Ana Kamga", "+999 000 000 1234", 5000));
        pastePayment(seller, sms(5000, "Ana Kamga", "+999 000 000 5678", "F-1"));   // another phone: R7, so not automatic
        assertThat(one("SELECT status FROM orders WHERE tenant_id = ? AND order_ref = 'F-1'", w.tenantId())).isEqualTo("PENDING_PAYMENT");
        UUID payment = UUID.fromString(one("SELECT id::text FROM payment WHERE tenant_id = ?", w.tenantId()));
        String page = seller.get("/app/payments/" + payment).body();
        assertThat(page).contains("The payment came from a different phone number than the one on the order.").contains("About this payment and one order.")
                .contains("A flag was raised on this payment, this order or this pair");
        String board = seller.get("/app").body();
        assertThat(board).contains("Flagged 1");
        String confirm = find("action=\"(/app/matches/[0-9a-f-]{36}/confirm)\"", board);
        assertThat(confirm).as("flags never take the seller's decision away (D8)").isNotNull();
        assertThat(path(post(seller, confirm, Map.of()))).isEqualTo("/app?n=MATCH_CONFIRMED");
        assertThat(one("SELECT status FROM orders WHERE tenant_id = ? AND order_ref = 'F-1'", w.tenantId())).isEqualTo("PAID");
    }

    private static final String FLOOR = "A flag was raised on this payment, this order or this pair";

    @Test
    void a_suggestion_of_a_payment_flagged_on_its_own_says_why_it_was_not_automatic() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        pasteOrder(seller, orderText("G-1", "Ana Kamga", "+999 000 000 1234", 5000));
        pastePayment(seller, forwarded(5000, "Ana Kamga", "1234"));   // no transaction id: R2, on the payment alone
        assertThat(rows("SELECT code || ' ' || (order_id IS NULL) FROM flag WHERE tenant_id = ?", w.tenantId())).containsExactly("NO_PROVIDER_ID true");
        UUID payment = UUID.fromString(one("SELECT id::text FROM payment WHERE tenant_id = ?", w.tenantId()));
        assertThat(one("SELECT count(*)::text FROM match WHERE tenant_id = ? AND state = 'SUGGESTED'", w.tenantId())).isEqualTo("1");
        assertThat(seller.get("/app/payments/" + payment).body()).contains(FLOOR);
    }

    @Test
    void a_suggestion_of_an_order_flagged_on_its_own_says_why_it_was_not_automatic() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        pasteOrder(seller, orderText("H-1", "Ana Kamga", "+999 000 000 1234", 3000));
        pasteOrder(seller, orderText("H-2", "Bob Synthetic", "+999 000 000 1234", 5000));   // the same phone under another name: R10 on the order
        pastePayment(seller, sms(5000, "Bob Synthetic", "+999 000 000 1234", "H-2"));
        assertThat(rows("SELECT subject_type || ' ' || code FROM flag WHERE tenant_id = ?", w.tenantId())).containsExactly("ORDER SENDER_ALIAS");
        UUID order = UUID.fromString(one("SELECT id::text FROM orders WHERE tenant_id = ? AND order_ref = 'H-2'", w.tenantId()));
        UUID payment = UUID.fromString(one("SELECT id::text FROM payment WHERE tenant_id = ?", w.tenantId()));
        assertThat(one("SELECT status FROM orders WHERE id = ?", order)).isEqualTo("PENDING_PAYMENT");
        assertThat(one("SELECT count(*)::text FROM match WHERE tenant_id = ? AND state = 'SUGGESTED' AND order_id = '" + order + "'", w.tenantId()))
                .isEqualTo("1");
        assertThat(seller.get("/app/payments/" + payment).body()).contains(FLOOR);
        assertThat(seller.get("/app/orders/" + order).body()).contains(FLOOR).contains("This phone number was used before under a different name.");
    }

    /** The service asks the database itself, so a helper (or a disabled seller) is refused even if a route forgot its permission. */
    @Test
    void the_service_refuses_a_user_who_is_not_an_enabled_seller_and_changes_nothing() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        pastePayment(seller, forwarded(5000, "Ana Kamga", "1234"));
        UUID flag = flagOf(w.tenantId(), "NO_PROVIDER_ID");
        assertThat(flags.resolve(w.tenantId(), flag, helper.userId()).outcome()).isEqualTo(FlagService.Outcome.NOT_A_SELLER);
        assertThat(flags.resolve(w.tenantId(), UUID.randomUUID(), w.sellerId()).outcome()).isEqualTo(FlagService.Outcome.NOT_FOUND);
        assertThat(one("SELECT (resolved_at IS NULL)::text FROM flag WHERE id = ?", flag)).isEqualTo("true");
        assertThat(flags.resolve(w.tenantId(), flag, w.sellerId()).outcome()).isEqualTo(FlagService.Outcome.RESOLVED);
        assertThat(flags.resolve(w.tenantId(), flag, w.sellerId()).outcome()).isEqualTo(FlagService.Outcome.ALREADY_RESOLVED);
    }
}
