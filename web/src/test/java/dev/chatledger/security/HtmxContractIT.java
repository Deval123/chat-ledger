package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a page must offer htmx: the CSRF token on the body as an attribute (not a script), htmx
 * configured not to evaluate code or inject styles, and nothing inline. The token taken from the page
 * is the one that works as a request header.
 *
 * <p>Requirement: 9.1. Task 3.8.
 */
class HtmxContractIT extends AbstractWebIT {

    private static final Pattern HX_HEADERS = Pattern.compile("<body[^>]*hx-headers=\"\\{&quot;([^&]+)&quot;:&quot;([^&]+)&quot;(?:,&quot;Accept&quot;:&quot;text/html&quot;)?\\}\"");

    @Test
    void the_signed_in_page_carries_a_working_csrf_header_for_htmx() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        Browser.Response page = b.get("/app");

        Matcher m = HX_HEADERS.matcher(page.body());
        assertThat(m.find()).as("hx-headers on <body>").isTrue();
        String headerName = m.group(1);
        String token = m.group(2);
        assertThat(headerName).isEqualTo("X-CSRF-TOKEN");

        assertThat(b.post("/app/probe/record", Map.of(), "HX-Request", "true", headerName, token).status()).isEqualTo(200);
        assertThat(b.post("/app/probe/record", Map.of(), "HX-Request", "true", headerName, token + "x").status()).isEqualTo(403);
        assertThat(b.post("/app/probe/record", Map.of(), "HX-Request", "true").status()).isEqualTo(403);
    }

    @Test
    void the_page_configures_htmx_to_match_the_content_security_policy() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        for (Browser.Response page : new Browser.Response[]{b.get("/app"), browser().get("/login")}) {
            assertThat(page.body()).contains("name=\"htmx-config\"").contains("&quot;allowEval&quot;:false".replace("&quot;", "\""))
                    .contains("\"allowScriptTags\":false").contains("\"includeIndicatorStyles\":false")
                    .contains("\"selfRequestsOnly\":true").contains("\"historyEnabled\":false");
        }
    }

    @Test
    void rendered_pages_contain_no_inline_code_and_the_token_is_not_in_a_script() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        for (String html : new String[]{b.get("/app").body(), browser().get("/login").body()}) {
            // The one script is the vendored htmx, from this origin, by its hashed name; anything else would be inline code.
            assertThat(html).containsPattern("<script src=\"/assets/htmx-2\\.0\\.11\\.min-[0-9a-f]{32}\\.js\" defer></script>");
            html = html.replaceAll("<script src=\"/assets/htmx-2\\.0\\.11\\.min-[0-9a-f]{32}\\.js\" defer></script>", "");
            assertThat(html).doesNotContain("<!--").doesNotContainIgnoringCase("<script").doesNotContainIgnoringCase("<style")
                    .doesNotContainPattern("(?i)\\sstyle\\s*=").doesNotContainPattern("(?i)\\son[a-z]+\\s*=");
        }
    }
}
