package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The board end to end through the real application: paste, automatic match, suggestion and confirmation, undo with its check page, a
 * total for an order that needs review, set aside and take back, the books check, the summary, the rematch limit. Each step is checked in
 * the database as well as on the page. Synthetic names and numbers only.
 *
 * <p>Requirements: 1, 3, 4, 5, 6, 9. Task 8.
 */
class BoardFlowIT extends BoardIT {

    private static final String PHONE = "+999 000 000 1234";

    @Test
    void an_exact_payment_from_the_seller_matches_automatically_and_can_be_undone_through_its_check_page() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());

        Browser.Response order = pasteOrder(b, orderText("A-1042", "Ana Kamga", PHONE, 5000));
        assertThat(order.status()).isEqualTo(302);
        assertThat(path(order)).startsWith("/app?n=ORDER_RECORDED&r=");
        String board = b.get(path(order)).body();
        assertThat(board).contains("Order recorded.").contains("Awaiting 1").contains("Ana Kamga").contains("5,000 XAF");
        assertThat(board).as("task 7, D13: the suspicion rules exist, so the Flagged column no longer says it is partial")
                .doesNotContain("Flags from the other suspicion rules arrive with a later step.").doesNotContain("Partial");

        Browser.Response payment = pastePayment(b, sms(5000, "Ana Kamga", PHONE, "A-1042"));
        assertThat(path(payment)).startsWith("/app?n=PAYMENT_RECORDED&r=");
        UUID orderId = orderId(w.tenantId(), "A-1042");
        assertThat(one("SELECT status FROM orders WHERE id = ?", orderId)).isEqualTo("PAID");
        String after = b.get("/app").body();
        assertThat(after).contains("Matched 1").contains("Awaiting 0").contains("Paid");

        // Undo: the check page first, then the reversal.
        UUID matchId = UUID.fromString(one("SELECT id::text FROM match WHERE tenant_id = ? AND state = 'AUTO'", w.tenantId()));
        Browser.Response check = b.get("/app/matches/" + matchId + "/undo");
        assertThat(check.status()).isEqualTo(200);
        assertThat(check.body()).contains("Undo this match?").contains("5,000 XAF");
        assertThat(one("SELECT state FROM match WHERE id = ?", matchId)).isEqualTo("AUTO");
        Browser.Response undone = post(b, "/app/matches/" + matchId + "/unmatch", Map.of());
        assertThat(path(undone)).isEqualTo("/app?n=MATCH_UNDONE");
        assertThat(one("SELECT state FROM match WHERE id = ?", matchId)).isEqualTo("REVERSED");
        assertThat(one("SELECT status FROM orders WHERE id = ?", orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(b.get("/app").body()).contains("Awaiting 1");
        assertThat(b.get(path(undone)).body()).contains("Match undone. The posting was reversed.");

        Browser.Response books = b.get("/app/books");
        assertThat(books.status()).isEqualTo(200);
        assertThat(books.body()).contains("The books balance.").doesNotContain("DOES NOT AGREE");
    }

    @Test
    void a_payment_a_helper_recorded_is_only_suggested_and_the_seller_confirms_it_from_the_card_with_or_without_htmx() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        Browser helperBrowser = signedIn(w.slug(), helper.email(), helper.password());
        pasteOrder(seller, orderText("B-7", "Bea Nkomo", PHONE, 4000));
        pasteOrder(seller, orderText("B-8", "Bea Nkomo", PHONE, 4000));
        Browser.Response recorded = pastePayment(helperBrowser, sms(4000, "Bea Nkomo", PHONE, "B-7"));
        assertThat(path(recorded)).startsWith("/app?n=PAYMENT_RECORDED");
        assertThat(one("SELECT status FROM orders WHERE tenant_id = ? AND order_ref = 'B-7'", w.tenantId())).isEqualTo("PENDING_PAYMENT");

        // The seller sees the suggestion with Confirm and Reject; the helper sees a sentence and no button.
        String sellerBoard = seller.get("/app").body();
        assertThat(sellerBoard).contains("Review 1").contains(">Confirm<").contains(">Reject<");
        String helperBoard = helperBrowser.get("/app").body();
        assertThat(helperBoard).contains("A suggestion waits for the seller.").doesNotContain(">Confirm<").doesNotContain("/confirm");

        // The payment page says why, in sentences made from codes, and what a confirmation would do.
        UUID helperPayment = UUID.fromString(one("SELECT id::text FROM payment WHERE tenant_id = ?", w.tenantId()));
        String detail = seller.get("/app/payments/" + helperPayment).body();
        assertThat(detail).contains("Suggested, score").contains("The amount is exactly what the order still needs.").contains("The order code is in the payment note.")
                .contains("Why this is not automatic, as of now:").contains("Recorded by a helper, so the seller must confirm.")
                .contains("Confirming would apply 4,000 XAF and keep 0 XAF as customer credit.");
        assertThat(helperBrowser.get("/app/payments/" + helperPayment).body()).contains("Suggested, score").doesNotContain(">Confirm<");

        String confirmPath = find("action=\"(/app/matches/[0-9a-f-]{36}/confirm)\"", sellerBoard);
        assertThat(confirmPath).isNotNull();
        // htmx: the card is replaced by a one-line result; the same handler, the same decision.
        Browser.Response viaHtmx = htmx(seller, confirmPath, Map.of());
        assertThat(viaHtmx.status()).isEqualTo(200);
        assertThat(viaHtmx.body()).contains("Confirmed and applied to the order.").contains("hx-swap-oob=\"true\"").doesNotContain("<html");
        assertThat(one("SELECT status FROM orders WHERE tenant_id = ? AND order_ref = 'B-7'", w.tenantId())).isEqualTo("PAID");
        // A second click is a plain sentence, not an error page.
        Browser.Response again = post(seller, confirmPath, Map.of());
        assertThat(path(again)).isEqualTo("/app?n=REFUSED_PAYMENT_NOT_FREE");
        assertThat(seller.get(path(again)).body()).contains("That payment is already matched or cannot be matched.");
    }

    @Test
    void the_seller_can_reject_a_suggestion_and_match_by_hand_and_set_a_payment_aside_and_take_it_back() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        Browser helperBrowser = signedIn(w.slug(), helper.email(), helper.password());
        pasteOrder(seller, orderText("C-1", "Cy Mbarga", PHONE, 2500));
        pastePayment(helperBrowser, sms(2500, "Cy Mbarga", PHONE, "C-1"));
        String rejectPath = find("action=\"(/app/matches/[0-9a-f-]{36}/reject)\"", seller.get("/app").body());
        assertThat(path(post(seller, rejectPath, Map.of()))).isEqualTo("/app?n=MATCH_REJECTED");

        UUID paymentId = UUID.fromString(one("SELECT id::text FROM payment WHERE tenant_id = ?", w.tenantId()));
        UUID orderId = orderId(w.tenantId(), "C-1");
        Browser.Response picker = seller.get("/app/payments/" + paymentId + "/match");
        assertThat(picker.status()).isEqualTo(200);
        assertThat(picker.body()).contains("Match this payment to an order").contains("Cy Mbarga").contains(orderId.toString());
        assertThat(path(post(seller, "/app/matches/manual", Map.of("orderId", orderId.toString(), "paymentId", paymentId.toString()))))
                .isEqualTo("/app?n=MATCH_MANUAL");
        assertThat(one("SELECT status FROM orders WHERE id = ?", orderId)).isEqualTo("PAID");
        assertThat(one("SELECT state FROM match WHERE order_id = ? AND payment_id = ? AND state = 'CONFIRMED'", orderId, paymentId)).isEqualTo("CONFIRMED");

        // Set aside and take back, on a payment nobody can match.
        pastePayment(seller, sms(900, "Dee Ndi", "+999 000 000 7777", null));
        UUID lone = UUID.fromString(one("SELECT id::text FROM payment WHERE tenant_id = ? AND amount_minor = 900", w.tenantId()));
        assertThat(path(post(seller, "/app/payments/" + lone + "/set-aside", Map.of()))).isEqualTo("/app?n=PAYMENT_SET_ASIDE");
        assertThat(one("SELECT status FROM payment WHERE id = ?", lone)).isEqualTo("UNMATCHED");
        String home = seller.get("/app").body();
        assertThat(home).contains("Payments set aside or repeated: 1");
        assertThat(seller.get("/app/columns/aside").body()).contains("Set aside by the seller").contains("Take back");
        assertThat(path(post(seller, "/app/payments/" + lone + "/take-back", Map.of()))).isEqualTo("/app?n=PAYMENT_TAKEN_BACK");
        assertThat(one("SELECT status FROM payment WHERE id = ?", lone)).isEqualTo("RECEIVED");
    }

    @Test
    void an_order_that_needs_review_gets_its_total_from_the_seller_and_becomes_payable_and_posted() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        Browser helperBrowser = signedIn(w.slug(), helper.email(), helper.password());
        pasteOrder(seller, "Order: R-5\nName: Eli Tabi\nPhone: " + PHONE + "\n2 x mystery basket");
        UUID id = orderId(w.tenantId(), "R-5");
        assertThat(one("SELECT status FROM orders WHERE id = ?", id)).isEqualTo("NEEDS_REVIEW");
        assertThat(seller.get("/app").body()).contains("Review 1").contains("Check the total");
        assertThat(helperBrowser.get("/app").body()).contains("Needs the seller to check.").doesNotContain("Check the total");

        Browser.Response review = seller.get("/app/orders/" + id + "/review");
        assertThat(review.status()).isEqualTo(200);
        assertThat(review.body()).contains("Check this order").contains("mystery basket").contains("Check the amount");
        assertThat(helperBrowser.get("/app/orders/" + id + "/review").status()).isEqualTo(403);

        assertThat(path(post(seller, "/app/orders/" + id + "/approve", Map.of("total", "abc")))).isEqualTo("/app?n=APPROVE_NOT_A_NUMBER");
        assertThat(path(post(seller, "/app/orders/" + id + "/approve", Map.of("total", "0")))).isEqualTo("/app?n=APPROVE_TOTAL_INVALID");
        assertThat(one("SELECT status FROM orders WHERE id = ?", id)).isEqualTo("NEEDS_REVIEW");
        assertThat(post(helperBrowser, "/app/orders/" + id + "/approve", Map.of("total", "3000")).status()).isEqualTo(403);
        assertThat(one("SELECT status FROM orders WHERE id = ?", id)).isEqualTo("NEEDS_REVIEW");

        // The second look: the amount as it will be posted; nothing is written by this step.
        Browser.Response second = post(seller, "/app/orders/" + id + "/approve", Map.of("total", "3000"));
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body()).contains("Make this order payable for 3,000 XAF?").contains("name=\"confirm\" value=\"yes\"").contains("value=\"3000\"");
        assertThat(one("SELECT status FROM orders WHERE id = ?", id)).isEqualTo("NEEDS_REVIEW");
        for (String notYes : new String[]{"no", "YES", "", "yes "}) {
            assertThat(post(seller, "/app/orders/" + id + "/approve", Map.of("total", "3000", "confirm", notYes)).status()).as(notYes).isEqualTo(200);
        }
        assertThat(one("SELECT count(*)::text FROM ledger_txn WHERE tenant_id = ? AND ref_id = ?", w.tenantId(), id)).isEqualTo("0");
        assertThat(path(post(seller, "/app/orders/" + id + "/approve", Map.of("total", "3000", "confirm", "yes")))).isEqualTo("/app?n=ORDER_APPROVED");
        assertThat(one("SELECT status || ' ' || total_minor FROM orders WHERE id = ?", id)).isEqualTo("PENDING_PAYMENT 3000");
        assertThat(one("SELECT count(*)::text FROM ledger_txn WHERE tenant_id = ? AND kind = 'ORDER_ACCEPTED' AND ref_id = ?", w.tenantId(), id)).isEqualTo("1");
        assertThat(one("SELECT count(*)::text FROM audit_log WHERE tenant_id = ? AND action = 'ORDER_APPROVED' AND subject = ?", w.tenantId(), id.toString()))
                .isEqualTo("1");
        assertThat(path(post(seller, "/app/orders/" + id + "/approve", Map.of("total", "4000")))).isEqualTo("/app?n=APPROVE_NOT_REVIEWABLE");
        assertThat(path(post(seller, "/app/orders/" + id + "/approve", Map.of("total", "4000", "confirm", "yes")))).isEqualTo("/app?n=APPROVE_NOT_REVIEWABLE");
        assertThat(one("SELECT total_minor::text FROM orders WHERE id = ?", id)).isEqualTo("3000");
        assertThat(seller.get("/app/books").body()).contains("The books balance.");
    }

    @Test
    void the_summary_counts_the_day_and_a_helper_sees_counts_but_no_money() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        Browser helperBrowser = signedIn(w.slug(), helper.email(), helper.password());
        pasteOrder(seller, orderText("S-1", "Fay Etoga", PHONE, 5000));
        pasteOrder(seller, orderText("S-2", "Gus Ndzi", "+999 000 000 5555", 1200));
        pastePayment(seller, sms(5000, "Fay Etoga", PHONE, "S-1"));
        pasteOrder(seller, "just some words with no order in them");

        // The orders got their time from the database and the payment its paid time from the message, written from the shared test clock,
        // which other tests may have moved on by hours. So each figure is asked for on its own day.
        String day = one("SELECT (min(created_at) AT TIME ZONE 'UTC')::date::text FROM orders WHERE tenant_id = ?", w.tenantId());
        String paidDay = one("SELECT (paid_at AT TIME ZONE 'UTC')::date::text FROM payment WHERE tenant_id = ?", w.tenantId());
        String page = seller.get("/app/summary?date=" + day).body();
        assertThat(page).contains("2 orders recorded").contains("1 of them paid").contains("0 items flagged now")
                .contains("Still owed on all open orders now: 1,200 XAF");
        assertThat(seller.get("/app/summary?date=" + paidDay).body()).contains("Collected (payments with a transaction id): 5,000 XAF");
        String forHelper = helperBrowser.get("/app/summary?date=" + day).body();
        assertThat(forHelper).contains("2 orders recorded").contains("1 of them paid")
                .doesNotContain("Still owed").doesNotContain("Collected (").doesNotContain("XAF").doesNotContain("5,000").doesNotContain("1,200");
        String helperBoard = helperBrowser.get("/app").body();
        assertThat(helperBoard).doesNotContain("Still owed").doesNotContain("Collected:");
        assertThat(seller.get("/app/summary?date=2020-01-01").body()).contains("0 orders recorded");
        assertThat(seller.get("/app/summary?date=not-a-date").status()).isEqualTo(400);
        assertThat(seller.get("/app/summary?date=2999-01-01").status()).isEqualTo(400);
    }

    @Test
    void the_fourth_rematch_in_a_minute_is_refused_with_429_and_a_wait_time() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        for (int i = 0; i < 3; i++) {
            assertThat(path(post(seller, "/app/rematch", Map.of()))).isEqualTo("/app?n=REMATCHED");
        }
        Browser.Response refused = post(seller, "/app/rematch", Map.of());
        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.header("retry-after")).matches("[0-9]+");
        assertSecurityHeaders(refused, "429");
        // Another workspace has its own allowance.
        Workspace other = newWorkspace();
        Browser otherSeller = signedIn(other.slug(), other.email(), other.password());
        assertThat(path(post(otherSeller, "/app/rematch", Map.of()))).isEqualTo("/app?n=REMATCHED");
    }

    @Test
    void the_books_check_reports_a_mismatch_plainly_and_keeps_showing_it_until_it_passes() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        pasteOrder(seller, orderText("K-1", "Hal Ongo", PHONE, 5000));
        assertThat(seller.get("/app/books").body()).contains("The books balance.");
        // Break the books behind the application's back, as the schema owner (the ledger is append-only for everyone else).
        forgePosting(w.tenantId());
        // The cached answer is still the old one for five minutes, and the button forces a fresh run.
        assertThat(seller.get("/app/books").body()).contains("The books balance.");
        Browser.Response forced = post(seller, "/app/books/check", Map.of());
        assertThat(path(forced)).isEqualTo("/app/books");
        String bad = seller.get("/app/books").body();
        assertThat(bad).contains("These figures do not agree.").contains("DOES NOT AGREE").contains("Postings of matches equal the active matches");
        assertThat(seller.get("/app").body()).contains("Books: needs attention");
        assertThat(loggedTextAt(ch.qos.logback.classic.Level.ERROR)).contains("books do not balance").contains("MATCH_POSTINGS");
    }

    @Test
    void the_books_answer_is_kept_for_five_minutes_and_not_longer() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        pasteOrder(seller, orderText("T-1", "Ivy Ndo", PHONE, 5000));
        assertThat(seller.get("/app/books").body()).contains("The books balance.");
        forgePosting(w.tenantId());
        clock.advance(java.time.Duration.ofMinutes(4).plusSeconds(59));
        assertThat(seller.get("/app/books").body()).as("still the answer of four minutes ago").contains("The books balance.");
        clock.advance(java.time.Duration.ofSeconds(1));
        assertThat(seller.get("/app/books").body()).as("exactly five minutes: a new check").contains("These figures do not agree.");
    }
}
