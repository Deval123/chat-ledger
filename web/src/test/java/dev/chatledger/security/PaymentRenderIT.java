package dev.chatledger.security;

import dev.chatledger.extraction.HostilePayments;
import dev.chatledger.payments.PaymentIngestionResult;
import dev.chatledger.payments.PaymentIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Text from a confirmation is data on the way in and escaped on the way out. Hostile texts go through the real service into the
 * real database, then onto a page that prints them the way the payment list will; the HTML must carry them as text. The Spring
 * context also proves the payment service, its templates and its repository wire into the application.
 *
 * <p>Requirements: 9.5 (escape all rendered user text), 2. Task 5.
 */
class PaymentRenderIT extends AbstractWebIT {

    @Autowired
    PaymentIngestionService service;

    private static final List<String> HOSTILE = List.of("script as the sender name", "markup as the sender name", "sql as the sender name",
            "formula as the sender name", "template expression as the transaction id", "format string as the sender name",
            "log forging with CRLF", "message inside quotes");

    private static String recent() {
        return OffsetDateTime.now(ZoneOffset.ofHours(1)).minusMinutes(5).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    @Test
    void stored_hostile_text_is_escaped_in_the_page_and_the_page_still_passes_the_policy_and_the_lint() throws Exception {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        int shown = 0;
        for (String name : HOSTILE) {
            String externalId = "render-" + shown++;
            PaymentIngestionResult r = service.ingest(w.tenantId(), w.sellerId(), externalId, HostilePayments.get(name));
            assertThat(r.stored()).as(name).isTrue();

            Browser.Response page = b.get("/app/probe/payment/" + externalId);
            assertThat(page.status()).as(name).isEqualTo(200);
            // The layout loads the vendored htmx from this origin; that one tag is ours, anything else would come from the message.
            String html = page.body().replaceAll("<script src=\"/assets/htmx-2\\.0\\.11\\.min-[0-9a-f]{32}\\.js\" defer></script>", "");
            assertThat(html).as(name + ": no raw tag from the message").doesNotContainIgnoringCase("<script")
                    .doesNotContainIgnoringCase("<img").doesNotContain("<b>").doesNotContain("<!--").doesNotContain("<![CDATA[");
            if (HostilePayments.get(name).contains("<")) {
                assertThat(html).as(name + ": the message's own markup is present, as escaped text").contains("&lt;");
            }
            assertThat(html).as(name + ": nothing from the message becomes a link or an attribute value")
                    .doesNotContainIgnoringCase("href=\"javascript").doesNotContainIgnoringCase("src=\"javascript");
            assertSecurityHeaders(page, name);
        }
        // The text really is there as text, not dropped.
        assertThat(b.get("/app/probe/payment/render-0").body())
                .contains("&lt;script&gt;alert(&#39;" + HostilePayments.CANARY + "&#39;)&lt;/script&gt;");
        // A template expression is printed, never evaluated: 7*7 stays "7*7", never "49".
        assertThat(b.get("/app/probe/payment/render-4").body()).contains("${7*7}").doesNotContain(">49<");

        assertThat(TemplateLintTest.violations("probe/payment.html",
                Files.readString(Path.of("src/test/resources/templates/probe/payment.html")))).isEmpty();
    }

    @Test
    void a_parsed_payment_prints_its_fields_escaped_too() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        String text = "You received 700 XAF from Cy-Lou O'Neil (+999 000 000 4321). Txn PA-88-CD34. Ref b12. " + recent();
        PaymentIngestionResult r = service.ingest(w.tenantId(), w.sellerId(), "parsed-1", text);
        assertThat(r.outcome()).isEqualTo(PaymentIngestionResult.Outcome.CREATED);
        Browser.Response page = b.get("/app/probe/payment/parsed-1");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("Cy-Lou O&#39;Neil").contains("PA-88-CD34").contains("b12").contains("4321");
        // The raw text, kept verbatim, is on the page too (escaped); the stored number-hash is not.
        assertThat(page.body()).doesNotContainPattern("[0-9a-f]{64}");
    }

    @Test
    void another_tenant_cannot_view_the_payment_and_gets_the_plain_error_page() {
        Workspace a = newWorkspace();
        Workspace other = newWorkspace();
        String text = "You received 5000 XAF from Ana K. (+999 000 000 1234). Txn PA7K2M9X. " + recent();
        assertThat(service.ingest(a.tenantId(), null, "private-1", text).stored()).isTrue();
        Browser owner = signedIn(a.slug(), a.email(), a.password());
        Browser stranger = signedIn(other.slug(), other.email(), other.password());
        assertThat(owner.get("/app/probe/payment/private-1", "Accept", "text/html").status()).isEqualTo(200);
        Browser.Response denied = stranger.get("/app/probe/payment/private-1", "Accept", "text/html");
        assertThat(denied.status()).isEqualTo(404);
        assertThat(denied.body()).doesNotContain("Ana").doesNotContain("private-1").doesNotContain("PA7K2M9X");
    }

    @Test
    void ingestion_through_the_real_application_context_logs_no_message_text() {
        Workspace w = newWorkspace();
        int i = 0;
        for (String name : HOSTILE) {
            service.ingest(w.tenantId(), null, "log-" + i++, HostilePayments.get(name));
        }
        service.ingest(w.tenantId(), null, "log-valid",
                "You received 5000 XAF from Ana K. (+999 000 000 1234). Txn PA7K2M9X. " + recent());
        String log = loggedText();
        assertThat(log).contains("payment ingested");
        for (String marker : new String[]{HostilePayments.CANARY, "forged log entry", "Ana ", "onerror", "<script>", "DROP TABLE",
                "9990000001234", "999 000 000", "PA7K2M9X", "TX123456"}) {
            assertThat(log).as("log must not contain: " + marker).doesNotContain(marker);
        }
    }
}
