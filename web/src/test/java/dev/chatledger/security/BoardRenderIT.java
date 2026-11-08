package dev.chatledger.security;

import dev.chatledger.extraction.HostileCorpus;
import dev.chatledger.orders.IngestionResult;
import dev.chatledger.orders.OrderIngestionService;
import dev.chatledger.payments.PaymentIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What reaches a page, and what does not. The whole hostile corpus goes through the real services into the real database and then onto every
 * page the seller and the helper can open; the markup must carry it as text. A helper never receives the text of a message. The weight and
 * asset rules live here too.
 *
 * <p>Requirements: 9.1, 9.5. Task 8.
 */
class BoardRenderIT extends BoardIT {

    @Autowired
    OrderIngestionService orders;
    @Autowired
    PaymentIngestionService payments;

    private static final Pattern HTMX_TAG = Pattern.compile("<script src=\"/assets/htmx-2\\.0\\.11\\.min-[0-9a-f]{32}\\.js\" defer></script>");

    /** The page without its one allowed script tag; what is left must hold no live code. */
    private static String withoutHtmx(String html) {
        return HTMX_TAG.matcher(html).replaceAll("");
    }

    private static void assertNoLiveMarkup(String html, String what) {
        String page = withoutHtmx(html);
        assertThat(page).as(what).doesNotContainIgnoringCase("<script").doesNotContainIgnoringCase("<img").doesNotContainIgnoringCase("<svg")
                .doesNotContainIgnoringCase("<iframe").doesNotContainIgnoringCase("<object").doesNotContainIgnoringCase("<embed")
                .doesNotContain("<!--").doesNotContain("<![CDATA[").doesNotContainPattern("(?is)<[^>]*\\s(on[a-z]+|style)\\s*=").doesNotContainPattern("(?i)(href|src|action)\\s*=\\s*\"\\s*javascript:")
                .doesNotContain(">49<");
    }

    @Test
    void the_hostile_corpus_is_text_on_every_page_for_the_seller_and_the_helper_plain_and_with_htmx() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        Browser helperBrowser = signedIn(w.slug(), helper.email(), helper.password());
        int n = 0;
        for (String name : HostileCorpus.names()) {
            String text = HostileCorpus.get(name);
            clock.advance(java.time.Duration.ofSeconds(3));   // the ingestion limit is 1 a second after a burst of 30
            orders.ingest(w.tenantId(), w.sellerId(), "hostile-order-" + n, text);
            payments.ingest(w.tenantId(), w.sellerId(), "hostile-payment-" + n, text);
            n++;
        }
        List<String> orderIds = rows("SELECT id::text FROM orders WHERE tenant_id = ?", w.tenantId());
        List<String> paymentIds = rows("SELECT id::text FROM payment WHERE tenant_id = ?", w.tenantId());
        assertThat(orderIds).hasSizeGreaterThan(40);
        assertThat(paymentIds).isNotEmpty();

        List<String> pages = new ArrayList<>(List.of("/app", "/app/summary", "/app/columns/awaiting", "/app/columns/review", "/app/columns/flagged",
                "/app/columns/unparsed", "/app/columns/matched", "/app/columns/aside"));
        orderIds.forEach(id -> pages.add("/app/orders/" + id));
        paymentIds.forEach(id -> pages.add("/app/payments/" + id));
        for (Browser who : new Browser[]{seller, helperBrowser}) {
            for (String path : pages) {
                Browser.Response page = who.get(path);
                assertThat(page.status()).as(path).isEqualTo(200);
                assertNoLiveMarkup(page.body(), path);
                assertSecurityHeaders(page, path);
                assertThat(page.body()).as(path).doesNotContain("<b>A\u00EFcha</b>");
            }
        }
        // The board holds the hostile names as escaped text.
        String board = seller.get("/app/columns/review").body() + seller.get("/app/columns/awaiting").body() + seller.get("/app/columns/unparsed").body();
        assertThat(board).doesNotContain("${7*7}").doesNotContain("[[${");
        // Pasting hostile text through the forms (plain and htmx) gives only fixed sentences back.
        for (String name : List.of("script tag as an item", "markup as a name", "template expression in an item", "log forging with CRLF", "long label")) {
            String text = HostileCorpus.get(name);
            Browser.Response plain = pasteOrder(seller, text);
            assertThat(plain.status()).as(name).isEqualTo(302);
            assertThat(path(plain)).as(name).matches("/app\\?n=[A-Z_]+(&r=[0-9a-f-]{36})?");
            Browser.Response viaHtmx = htmx(seller, "/app/orders", Map.of("mid", UUID.randomUUID().toString(), "text", text));
            assertThat(viaHtmx.status()).as(name).isEqualTo(200);
            assertNoLiveMarkup(viaHtmx.body(), name + " (htmx)");
            assertThat(viaHtmx.body()).as(name + ": nothing of the message comes back").doesNotContain(HostileCorpus.CANARY);
        }
        // A message above the request limit never reaches the application.
        assertThat(pasteOrder(seller, HostileCorpus.get("huge input")).status()).isEqualTo(413);
        // Query values are typed and never echoed.
        for (String bad : List.of("/app?n=%3Cscript%3Ealert(1)%3C/script%3E", "/app?n=NOT_A_NOTICE", "/app?r=%3Cscript%3E")) {
            Browser.Response r = seller.get(bad);
            assertThat(r.body()).as(bad).doesNotContain("alert(1)").doesNotContain("NOT_A_NOTICE");
        }
    }

    @Test
    void a_helper_never_receives_the_text_of_a_message_and_the_seller_sees_it_masked_and_only_where_it_is_needed() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        Browser helperBrowser = signedIn(w.slug(), helper.email(), helper.password());
        String unreadable = "RAWCANARY-unparsed call me on 0612345678 thanks";
        orders.ingest(w.tenantId(), w.sellerId(), "raw-1", unreadable);
        orders.ingest(w.tenantId(), w.sellerId(), "raw-2", "Order: RAW-2\nName: Raw Review\nPhone: +999 000 000 1234\n2 x mystery basket");
        orders.ingest(w.tenantId(), w.sellerId(), "raw-3", "Order: RAW-3\nName: Raw Fine\nPhone: +999 000 000 4321\n1 x rice @ 100");
        payments.ingest(w.tenantId(), w.sellerId(), "raw-4", "RAWCANARY-payment-unparsed sender 0698765432");
        String unparsedId = one("SELECT id::text FROM orders WHERE tenant_id = ? AND external_id = 'raw-1'", w.tenantId());
        String reviewId = one("SELECT id::text FROM orders WHERE tenant_id = ? AND external_id = 'raw-2'", w.tenantId());
        String fineId = one("SELECT id::text FROM orders WHERE tenant_id = ? AND external_id = 'raw-3'", w.tenantId());
        String paymentId = one("SELECT id::text FROM payment WHERE tenant_id = ?", w.tenantId());

        // The seller: masked text on the pages of an item that was not read or needs review, nothing on a normal order.
        String unparsedPage = seller.get("/app/orders/" + unparsedId).body();
        assertThat(unparsedPage).contains("Message as received (not checked)").contains("RAWCANARY-unparsed").contains("\u2022\u2022\u2022\u2022\u2022\u20225678")
                .doesNotContain("0612345678");
        String reviewPage = seller.get("/app/orders/" + reviewId + "/review").body();
        assertThat(reviewPage).contains("Message as received").contains("mystery basket").doesNotContain("000 000 1234").contains("Check the amount");
        assertThat(one("SELECT status FROM orders WHERE id = ?::uuid", fineId)).isEqualTo("PENDING_PAYMENT");
        assertThat(seller.get("/app/orders/" + fineId).body()).doesNotContain("000 000 4321").doesNotContain("Message as received").contains("4321");
        assertThat(seller.get("/app/payments/" + paymentId).body()).contains("RAWCANARY-payment-unparsed").doesNotContain("0698765432")
                .contains("\u2022\u2022\u2022\u2022\u2022\u20225432");

        // A payment that was read normally shows no raw text, even to the seller.
        payments.ingest(w.tenantId(), w.sellerId(), "raw-5", sms(4321, "Raw Received", "+999 000 000 8765", null));
        String receivedId = one("SELECT id::text FROM payment WHERE tenant_id = ? AND external_id = 'raw-5'", w.tenantId());
        assertThat(one("SELECT status FROM payment WHERE id = ?::uuid", receivedId)).isEqualTo("RECEIVED");
        assertThat(seller.get("/app/payments/" + receivedId).body()).contains("Raw Received").doesNotContain("Message as received");

        // The helper: no template carries any of it, and the review page is refused.
        List<String> paths = List.of("/app", "/app/summary", "/app/columns/unparsed", "/app/columns/review", "/app/columns/awaiting",
                "/app/orders/" + unparsedId, "/app/orders/" + reviewId, "/app/orders/" + fineId, "/app/payments/" + paymentId);
        for (String path : paths) {
            Browser.Response page = helperBrowser.get(path);
            assertThat(page.status()).as(path).isEqualTo(200);
            assertThat(page.body()).as(path).doesNotContain("RAWCANARY").doesNotContain("Message as received").doesNotContain("0612345678")
                    .doesNotContain("0698765432").doesNotContain("000 000 1234").doesNotContain("class=\"raw");
        }
        assertThat(helperBrowser.get("/app/orders/" + reviewId + "/review").status()).isEqualTo(403);
        for (String file : List.of("orders", "payments", "ledger")) {
            Browser.Response r = helperBrowser.get("/app/export/" + file + ".csv");
            assertThat(r.status()).as(file).isEqualTo(403);
            assertThat(r.body()).doesNotContain("RAWCANARY").doesNotContain("id\"");
        }
        assertThat(helperBrowser.get("/app/books").status()).isEqualTo(403);
        assertThat(helperBrowser.get("/app/export").status()).isEqualTo(403);
        String helperBoard = helperBrowser.get("/app").body();
        assertThat(helperBoard).doesNotContain("/app/books").doesNotContain("/app/export").doesNotContain("Look again for matches");
        assertThat(helperBoard).contains("Paste an order").contains("Paste a payment");
    }

    @Test
    void assets_are_public_hashed_and_cached_while_pages_are_never_cached() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        String html = seller.get("/app").body();
        String css = find("href=\"(/assets/app-[0-9a-f]{32}\\.css)\"", html);
        String js = find("src=\"(/assets/htmx-2\\.0\\.11\\.min-[0-9a-f]{32}\\.js)\"", html);
        assertThat(css).isNotNull();
        assertThat(js).isNotNull();
        for (String asset : List.of(css, js)) {
            Browser.Response anonymous = browser().get(asset);
            assertThat(anonymous.status()).as(asset).isEqualTo(200);
            assertThat(anonymous.header("cache-control")).as(asset).contains("max-age=31536000").contains("public").doesNotContain("no-store");
            assertThat(anonymous.header("x-content-type-options")).isEqualTo("nosniff");
            assertThat(anonymous.header("content-security-policy")).isEqualTo(EXPECTED_CSP);
        }
        assertThat(browser().get(css).header("content-type")).startsWith("text/css");
        assertThat(browser().get(js).header("content-type")).containsAnyOf("javascript", "ecmascript");
        assertThat(browser().get("/assets/app.css").status()).as("the plain name is not served with the long cache").isIn(200, 404);
        assertThat(browser().get("/assets/../application.properties").status()).isIn(400, 404);
        assertThat(browser().get("/assets/missing.png").status()).isEqualTo(404);
        assertSecurityHeaders(seller.get("/app"), "board");
        assertThat(seller.get("/app").header("cache-control")).contains("no-store");
        // Weight: one stylesheet and the script, small.
        assertThat(browser().get(css).body().length()).as("stylesheet").isLessThan(12 * 1024);
        assertThat(browser().get(js).body().length()).as("htmx").isLessThan(60 * 1024);
    }

    @Test
    void a_full_board_stays_small_and_shows_more_by_cursor_up_to_five_pages() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        for (int i = 0; i < 130; i++) {
            clock.advance(java.time.Duration.ofSeconds(2));
            IngestionResult r = orders.ingest(w.tenantId(), w.sellerId(), "bulk-" + i,
                    "Order: BULK-" + i + "\nName: Customer Number " + i + "\nPhone: +999 000 000 " + (1000 + i) + "\n1 x rice @ " + (100 + i));
            assertThat(r.stored()).isTrue();
        }
        Browser.Response board = seller.get("/app");
        assertThat(board.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).as("board html").isLessThan(60 * 1024);
        assertThat(board.body().split("class=\"card\"", -1).length - 1).isEqualTo(20);
        String next = find("href=\"(/app/columns/awaiting\\?after=[^\"]+)\"", board.body());
        assertThat(next).isNotNull();
        List<String> seen = new ArrayList<>();
        String current = next.replace("&amp;", "&");
        int pages = 1;
        while (current != null) {
            Browser.Response page = seller.get(current);
            assertThat(page.status()).isEqualTo(200);
            pages++;
            java.util.regex.Matcher m = Pattern.compile("id=\"c-([0-9a-f-]{36})\"").matcher(page.body());
            while (m.find()) {
                assertThat(seen).doesNotContain(m.group(1));
                seen.add(m.group(1));
            }
            String following = find("href=\"(/app/columns/awaiting\\?after=[^\"]+)\"", page.body());
            current = following == null ? null : following.replace("&amp;", "&");
        }
        assertThat(pages).as("at most five pages of twenty").isEqualTo(5);
        assertThat(seen).hasSize(80);
    }

    /**
     * A name that reached the database with characters the ingestion would have removed (written here as the schema owner, as an older or a
     * buggy writer could) is still cleaned on the card: no bidirectional override, no zero-width character, cut to 40 characters.
     */
    @Test
    void a_stored_name_is_cleaned_and_cut_on_the_card_whatever_wrote_it() {
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        UUID customer = UUID.randomUUID();
        asOwner("INSERT INTO customer(id, tenant_id, display_name) VALUES (?, ?, ?)", customer, w.tenantId(), "Ana\u202Eevil\u200Bname " + "x".repeat(80));
        asOwner("INSERT INTO orders(id, tenant_id, external_id, customer_id, total_minor, currency, status, raw_text) VALUES (?, ?, 'clean-1', ?, 100, 'XAF', 'PENDING_PAYMENT', 'synthetic')",
                UUID.randomUUID(), w.tenantId(), customer);
        String board = seller.get("/app").body();
        assertThat(board).contains("Anaevilname " + "x".repeat(28) + "\u2026").doesNotContain("\u202E").doesNotContain("\u200B").doesNotContain("x".repeat(30));
    }
}
