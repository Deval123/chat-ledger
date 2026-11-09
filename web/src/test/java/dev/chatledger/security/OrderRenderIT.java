package dev.chatledger.security;

import dev.chatledger.extraction.HostileCorpus;
import dev.chatledger.orders.IngestionResult;
import dev.chatledger.orders.OrderIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Text from a message is data on the way in and escaped on the way out. Hostile orders go through the real service
 * into the real database, then onto a page that prints them the way the board will; the HTML must carry them as text.
 * The Spring context also proves the ingestion service wires into the application.
 *
 * <p>Requirements: 9.5 (escape all rendered user text), 1.4. Task 4.
 */
class OrderRenderIT extends AbstractWebIT {

    @Autowired
    OrderIngestionService service;

    private static final List<String> HOSTILE = List.of("script tag as an item", "markup as a name", "javascript url as a note",
            "french with hostile name", "template expression in an item", "html comment and cdata", "sql in an item",
            "format string in a name", "log forging with CRLF");

    @Test
    void stored_hostile_text_is_escaped_in_the_page_and_the_page_still_passes_the_policy_and_the_lint() throws Exception {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        int shown = 0;
        for (String name : HOSTILE) {
            String externalId = "render-" + shown++;
            IngestionResult r = service.ingest(w.tenantId(), w.sellerId(), externalId, HostileCorpus.get(name));
            assertThat(r.stored()).as(name).isTrue();

            Browser.Response page = b.get("/app/probe/order/" + externalId);
            assertThat(page.status()).as(name).isEqualTo(200);
            // The layout loads the vendored htmx from this origin; that one tag is ours, anything else would come from the message.
            String html = page.body().replaceAll("<script src=\"/assets/htmx-2\\.0\\.11\\.min-[0-9a-f]{32}\\.js\" defer></script>", "");
            assertThat(html).as(name + ": no raw tag from the message").doesNotContainIgnoringCase("<script")
                    .doesNotContainIgnoringCase("<img").doesNotContain("<b>").doesNotContain("<!--").doesNotContain("<![CDATA[");
            if (HostileCorpus.get(name).contains("<")) {
                assertThat(html).as(name + ": the message's own markup is present, as escaped text").contains("&lt;");
            }
            assertThat(html).as(name + ": nothing from the message becomes a link or an attribute value")
                    .doesNotContainIgnoringCase("href=\"javascript").doesNotContainIgnoringCase("src=\"javascript");
            assertSecurityHeaders(page, name);
        }
        // A note with a javascript: URL is shown as plain text only.
        assertThat(b.get("/app/probe/order/render-2").body()).contains("javascript:alert(1)");
        // The text really is there as text, not dropped: the markup name from the corpus.
        Browser.Response markup = b.get("/app/probe/order/render-1");
        assertThat(markup.body()).contains("&lt;img src=x onerror=alert(&#39;" + HostileCorpus.CANARY + "&#39;)&gt;");
        // And a template expression in a label is printed, never evaluated: 7*7 stays "7*7", never "49".
        String expressions = b.get("/app/probe/order/render-4").body();
        assertThat(expressions).contains("${7*7}").doesNotContain(">49<");

        assertThat(TemplateLintTest.violations("probe/order.html",
                Files.readString(Path.of("src/test/resources/templates/probe/order.html")))).isEmpty();
    }

    @Test
    void another_tenant_cannot_view_the_order_and_gets_the_plain_error_page() {
        Workspace a = newWorkspace();
        Workspace other = newWorkspace();
        assertThat(service.ingest(a.tenantId(), null, "private-1", "Name: Ana\n1 x rice @ 500").stored()).isTrue();
        Browser owner = signedIn(a.slug(), a.email(), a.password());
        Browser stranger = signedIn(other.slug(), other.email(), other.password());
        assertThat(owner.get("/app/probe/order/private-1", "Accept", "text/html").status()).isEqualTo(200);
        Browser.Response denied = stranger.get("/app/probe/order/private-1", "Accept", "text/html");
        assertThat(denied.status()).isEqualTo(404);
        assertThat(denied.body()).doesNotContain("Ana").doesNotContain("private-1").doesNotContain("rice");
    }

    @Test
    void ingestion_through_the_real_application_context_logs_no_message_text() {
        Workspace w = newWorkspace();
        for (String name : HOSTILE) {
            service.ingest(w.tenantId(), null, "log-" + Math.abs(name.hashCode()), HostileCorpus.get(name));
        }
        String log = loggedText();
        assertThat(log).contains("order ingested");
        for (String marker : new String[]{HostileCorpus.CANARY, "forged log entry", "Ana ", "onerror", "<script>", "DROP TABLE", "tomato",
                "A\u00EFcha", "9990000001234", "999 000 000"}) {
            assertThat(log).as("log must not contain: " + marker).doesNotContain(marker);
        }
    }
}
