package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every page of a regular workspace, rendered by the real application on the real database, meets the structural accessibility rules
 * ({@link A11yRules}): a skip link first, one main, one h1 and no jump in headings, labelled fields, named controls with no two buttons
 * alike, one polite live region that is never hidden, valid references, distinct titles. Seen by the seller and by a helper.
 *
 * <p>Task 12a; WCAG 2.2 AA as far as markup shows it. The keyboard, focus, reflow and zoom are checked in a browser by the CI job.
 */
class AccessibilityIT extends BoardIT {

    private static final String PHONE = "+999 000 000 1234";

    @Test
    void every_page_meets_the_rules_for_the_seller_and_the_helper() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        Browser helperBrowser = signedIn(w.slug(), helper.email(), helper.password());
        // Two suggestions with different payers (two Confirm buttons that must not share a name), an automatic match, a review, an
        // unreadable message and a payment nobody can match.
        pasteOrder(seller, orderText("A-1", "Ana Kamga", PHONE, 5000));
        pasteOrder(seller, orderText("B-2", "Bea Nkomo", "+999 000 000 5678", 2000));
        pastePayment(helperBrowser, sms(5000, "Ana Kamga", PHONE, "A-1"));
        pastePayment(helperBrowser, sms(2000, "Bea Nkomo", "+999 000 000 5678", "B-2"));
        pasteOrder(seller, orderText("C-3", "Cy Mbarga", "+999 000 000 7777", 2500));
        pastePayment(seller, sms(2500, "Cy Mbarga", "+999 000 000 7777", "C-3"));
        pasteOrder(seller, "Order: R-5\nName: Eli Tabi\nPhone: +999 000 000 2468\n2 x mystery basket");   // a customer of its own
        pasteOrder(seller, "hello, is the shop open?");
        pastePayment(seller, sms(900, "Dee Ndi", "+999 000 000 4321", null));

        UUID tenant = w.tenantId();
        UUID review = orderId(tenant, "R-5");
        String suggestedPayment = one("SELECT payment_id::text FROM match WHERE tenant_id = ? AND state = 'SUGGESTED' LIMIT 1", tenant);
        String auto = one("SELECT id::text FROM match WHERE tenant_id = ? AND state = 'AUTO'", tenant);
        String lone = one("SELECT id::text FROM payment WHERE tenant_id = ? AND amount_minor = 900", tenant);
        assertThat(suggestedPayment).as("a suggestion to look at").isNotNull();
        assertThat(auto).as("an automatic match to undo").isNotNull();
        assertThat(rows("SELECT id FROM match WHERE tenant_id = ? AND state = 'SUGGESTED'", tenant)).as("two suggestions").hasSize(2);

        List<String> pages = new ArrayList<>(List.of("/app", "/app/summary", "/app/books", "/app/export",
                "/app/columns/awaiting", "/app/columns/review", "/app/columns/matched", "/app/columns/flagged", "/app/columns/unparsed",
                "/app/columns/aside", "/app/orders/" + orderId(tenant, "A-1"), "/app/orders/" + review, "/app/orders/" + review + "/review",
                "/app/payments/" + suggestedPayment, "/app/payments/" + lone, "/app/payments/" + lone + "/match", "/app/matches/" + auto + "/undo"));
        Map<String, String> titles = new LinkedHashMap<>();
        List<String> found = new ArrayList<>();
        for (String path : pages) {
            Browser.Response page = seller.get(path);
            assertThat(page.status()).as(path).isEqualTo(200);
            found.addAll(A11yRules.violations("seller " + path, page.body()));
            titles.put(path, org.jsoup.Jsoup.parse(page.body()).title());
        }
        // The second look before money enters the books, a check page reached by a post.
        Browser.Response approve = post(seller, "/app/orders/" + review + "/approve", Map.of("total", "3000"));
        assertThat(approve.status()).isEqualTo(200);
        found.addAll(A11yRules.violations("seller approve", approve.body()));
        for (String path : List.of("/app", "/app/summary", "/app/columns/review", "/app/orders/" + orderId(tenant, "A-1"))) {
            found.addAll(A11yRules.violations("helper " + path, helperBrowser.get(path).body()));
        }
        // Signed out: the sign-in page; and the error page.
        found.addAll(A11yRules.violations("/login", browser().get("/login").body()));
        Browser.Response missing = seller.get("/app/orders/" + UUID.randomUUID(), "Accept", "text/html");   // as a browser asks
        assertThat(missing.status()).isEqualTo(404);
        found.addAll(A11yRules.violations("404", missing.body()));
        assertThat(found).isEmpty();

        // Titles tell pages apart by purpose (2.4.2): each column and the summary's day are named. A title stays in the browser's
        // history and may be synced off the phone, so it never names a customer or carries an amount.
        assertThat(titles.get("/app/orders/" + orderId(tenant, "A-1"))).isEqualTo("Order - chat-ledger");
        assertThat(titles.get("/app/columns/review")).isEqualTo("Needs your attention - chat-ledger");
        assertThat(titles.get("/app/orders/" + review + "/review")).isEqualTo("Check an order - chat-ledger");
        assertThat(titles.get("/app/payments/" + lone + "/match")).isEqualTo("Match a payment - chat-ledger");
        assertThat(org.jsoup.Jsoup.parse(approve.body()).title()).isEqualTo("Make it payable - chat-ledger");
        assertThat(titles.values().stream().filter(t -> t.startsWith("Column")).toList()).isEmpty();
        List<String> all = new java.util.ArrayList<>(titles.values());
        all.add(org.jsoup.Jsoup.parse(approve.body()).title());
        assertThat(all).as("no title names a customer or an amount")
                .noneMatch(t -> t.matches(".*(Ana|Bea|Eli|Dee|Kamga|Tabi|Ndi|XAF|[0-9],[0-9]{3}).*"));
    }

    /** A column holds 20 cards on the board; its "Show more" link names the column, so a list of links tells them apart (2.4.4). */
    @Test
    void a_show_more_link_names_its_column() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        for (int i = 0; i < 21; i++) {
            pasteOrder(seller, orderText("M-" + (100 + i), "Ana Kamga", PHONE, 1000 + i));
        }
        org.jsoup.nodes.Document board = org.jsoup.Jsoup.parse(seller.get("/app").body());
        List<String> more = board.select("a[href*=page=2]").stream().map(A11yRules::name).toList();
        assertThat(more).containsExactly("Show more: Awaiting payment");
        assertThat(board.select("a[href*=page=2] > span:not(.sr)").text()).as("what is seen").isEqualTo("Show more");
    }

    @Test
    void the_buttons_of_a_card_name_the_card_and_the_visible_word_comes_first() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        Browser helperBrowser = signedIn(w.slug(), helper.email(), helper.password());
        pasteOrder(seller, orderText("A-1", "Ana Kamga", PHONE, 5000));
        pastePayment(helperBrowser, sms(5000, "Ana Kamga", PHONE, "A-1"));
        org.jsoup.nodes.Document board = org.jsoup.Jsoup.parse(seller.get("/app").body());
        List<String> names = board.select("form[action$=/confirm] button, form[action$=/reject] button").stream().map(A11yRules::name).toList();
        assertThat(names).hasSize(2).allMatch(n -> n.matches("(Confirm|Reject): Ana Kamga, 5,000 XAF, .+"));
        // The added words are visually hidden; what is seen is the word alone (2.5.3, label in name).
        assertThat(board.select("form[action$=/confirm] button > span:not(.sr)").text()).isEqualTo("Confirm");
        org.jsoup.nodes.Element card = board.selectFirst("article.card[aria-labelledby]");
        assertThat(card).isNotNull();
        // A card is named by its heading: the name that is seen, then (hidden) what it is, so a customer's order and her payment differ.
        org.jsoup.nodes.Element heading = board.getElementById(card.attr("aria-labelledby"));
        assertThat(heading.text()).matches("Ana Kamga \\((order|payment), 5,000 XAF, .+\\)");
        assertThat(heading.select("a > span:not(.sr)").text()).isEqualTo("Ana Kamga");
        List<String> links = board.select("article.card h3 a").stream().map(A11yRules::name).toList();
        assertThat(links).hasSize(2).doesNotHaveDuplicates()
                .anyMatch(n -> n.startsWith("Ana Kamga (order, ")).anyMatch(n -> n.startsWith("Ana Kamga (payment, "));
    }

    @Test
    void the_notice_region_stays_in_place_and_only_its_content_changes() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        Browser helperBrowser = signedIn(w.slug(), helper.email(), helper.password());
        pasteOrder(seller, orderText("A-1", "Ana Kamga", PHONE, 5000));
        pastePayment(helperBrowser, sms(5000, "Ana Kamga", PHONE, "A-1"));
        String confirm = find("action=\"(/app/matches/[0-9a-f-]{36}/confirm)\"", seller.get("/app").body());
        Browser.Response viaHtmx = htmx(seller, confirm, Map.of());
        org.jsoup.nodes.Document answer = org.jsoup.Jsoup.parse(viaHtmx.body());
        org.jsoup.nodes.Element notice = answer.getElementById("notice");
        assertThat(notice).isNotNull();
        assertThat(notice.attr("hx-swap-oob")).as("the text goes inside the region; the region is not replaced").isEqualTo("innerHTML");
        assertThat(notice.text()).isEqualTo("Confirmed and applied to the order.");
        // The result card takes the focus after the swap (htmx focuses [autofocus]).
        assertThat(answer.selectFirst("article.card.done[autofocus][tabindex=-1]")).isNotNull();
        // On a full page the region is there, empty, and takes no room (CSS :empty), never hidden.
        org.jsoup.nodes.Element empty = org.jsoup.Jsoup.parse(seller.get("/app").body()).getElementById("notice");
        assertThat(empty.childNodeSize()).as("empty, so CSS :empty applies").isZero();
        // A paste answers in the same region, with no second status element.
        String pasted = htmx(seller, "/app/orders", Map.of("mid", UUID.randomUUID().toString(), "text", orderText("B-2", "Bea Nkomo", PHONE, 2000))).body();
        org.jsoup.nodes.Document paste = org.jsoup.Jsoup.parse(pasted);
        assertThat(paste.select("[role=status]")).as("no second live region in the paste answer").isEmpty();
        assertThat(paste.getElementById("notice").attr("hx-swap-oob")).isEqualTo("innerHTML");
        assertThat(paste.getElementById("notice").text()).isNotBlank();
    }
}
