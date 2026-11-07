package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Size, encoding and content-type rules over real HTTP, including bodies with no declared length. The
 * default limits apply: login 4 KiB, JSON 16 KiB, everything else 64 KiB.
 *
 * <p>Requirement: 9.1. Task 3.9.
 */
class RequestGuardIT extends AbstractWebIT {

    private static final String FORM = "application/x-www-form-urlencoded";

    private static byte[] padding(int bytes) {
        byte[] b = new byte[bytes];
        Arrays.fill(b, (byte) 'x');
        return b;
    }

    private static byte[] json(int totalBytes) {
        // {"a":"xxx..."} with exactly totalBytes bytes
        String head = "{\"a\":\"";
        String tail = "\"}";
        return (head + "x".repeat(totalBytes - head.length() - tail.length()) + tail).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void a_login_body_over_four_kib_is_refused_whether_or_not_it_declares_its_length() {
        Browser b = browser();
        byte[] body = ("workspace=" + "x".repeat(5000)).getBytes(StandardCharsets.US_ASCII);
        Browser.Response declared = b.postRaw("/login", FORM, body, false);
        Browser.Response chunked = b.postRaw("/login", FORM, body, true);
        assertThat(declared.status()).as("declared length").isEqualTo(413);
        assertThat(chunked.status()).as("chunked, no declared length").isEqualTo(413);
        assertThat(chunked.body()).isEqualTo("Request too large.");
        assertSecurityHeaders(declared, "413");
    }

    @Test
    void a_chunked_sign_in_within_the_limit_works_so_the_guard_hands_on_exactly_what_it_read() {
        Workspace w = newWorkspace();
        Browser b = browser();
        String token = Browser.csrf(b.get("/login"));
        String form = "_csrf=" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8) + "&workspace=" + w.slug()
                + "&email=" + java.net.URLEncoder.encode(w.email(), StandardCharsets.UTF_8)
                + "&password=" + java.net.URLEncoder.encode(w.password(), StandardCharsets.UTF_8);
        Browser.Response r = b.postRaw("/login", FORM, form.getBytes(StandardCharsets.US_ASCII), true);
        assertThat(r.status()).isEqualTo(302);
        assertThat(r.header("location")).endsWith("/app");
        assertThat(b.get("/app").status()).isEqualTo(200);
    }

    @Test
    void json_has_a_sixteen_kib_limit_exactly() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        String token = Browser.csrf(b.get("/app"));
        String[] headers = {"X-CSRF-TOKEN", token};

        assertThat(b.postRaw("/app/probe/json", "application/json", json(1024), false, headers).body()).isEqualTo("length=1024");
        assertThat(b.postRaw("/app/probe/json", "application/json", json(16_384), false, headers).status()).as("exactly 16 KiB").isEqualTo(200);
        assertThat(b.postRaw("/app/probe/json", "application/json", json(16_384), true, headers).status()).as("exactly 16 KiB, chunked").isEqualTo(200);
        assertThat(b.postRaw("/app/probe/json", "application/json", json(16_385), false, headers).status()).as("one byte over").isEqualTo(413);
        assertThat(b.postRaw("/app/probe/json", "application/json", json(16_385), true, headers).status()).as("one byte over, chunked").isEqualTo(413);
        assertThat(b.postRaw("/app/probe/json", "application/json", json(200_000), true, headers).status()).isEqualTo(413);
        assertThat(b.get("/app").status()).as("still signed in").isEqualTo(200);
    }

    @Test
    void json_that_is_not_clean_utf8_is_refused() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        String[] headers = {"X-CSRF-TOKEN", Browser.csrf(b.get("/app"))};
        byte[] invalidUtf8 = {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, '"', '}'};
        byte[] nul = {'{', '"', 'a', '"', ':', '"', 0, '"', '}'};
        byte[] escape = {'{', '"', 'a', '"', ':', '"', 0x1B, '"', '}'};
        assertThat(b.postRaw("/app/probe/json", "application/json", invalidUtf8, false, headers).status()).isEqualTo(400);
        assertThat(b.postRaw("/app/probe/json", "application/json", nul, true, headers).status()).isEqualTo(400);
        assertThat(b.postRaw("/app/probe/json", "application/json", escape, false, headers).status()).isEqualTo(400);
        assertThat(b.postRaw("/app/probe/json", "application/json", "{\"a\":\"caf\u00e9\"}".getBytes(StandardCharsets.UTF_8), false, headers).status())
                .isEqualTo(200);
    }

    @Test
    void free_text_may_hold_line_breaks_but_not_control_characters() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        String token = Browser.csrf(b.get("/app"));
        Browser.Response ok = b.post("/app/probe/note", Map.of("_csrf", token, "text", "line one\nline two \u00e9"));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("note=line one\nline two \u00e9");

        for (String bad : new String[]{"%00", "%0B", "%1B", "%7F", "%FF", "%C3", "%"}) {
            byte[] body = ("_csrf=" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8) + "&text=a" + bad + "b")
                    .getBytes(StandardCharsets.US_ASCII);
            assertThat(b.postRaw("/app/probe/note", FORM, body, false).status()).as(bad).isEqualTo(400);
        }
    }

    @Test
    void multipart_is_not_accepted() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        byte[] body = "--x\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nb\r\n--x--\r\n".getBytes(StandardCharsets.US_ASCII);
        assertThat(b.postRaw("/app/probe/note", "multipart/form-data; boundary=x", body, false,
                "X-CSRF-TOKEN", Browser.csrf(b.get("/app"))).status()).isEqualTo(415);
    }

    @Test
    void a_bad_query_string_is_refused() {
        Browser b = browser();
        for (String query : new String[]{"?a=%FF", "?a=%C3%28", "?a=%1B", "?a=%00"}) {
            assertThat(b.get("/login" + query).status()).as(query).isEqualTo(400);
        }
        assertThat(b.get("/login?error").status()).isEqualTo(200);
        assertThat(b.get("/login?a=%C3%A9").status()).isEqualTo(200);
    }

    @Test
    void an_oversized_header_is_refused_by_the_container() {
        Browser b = browser();
        int status;
        try {
            status = b.get("/login", "X-Padding", "x".repeat(20_000)).status();
        } catch (IllegalStateException connectionClosed) {
            status = 400;   // the container may drop the connection instead of answering
        }
        assertThat(status).isIn(400, 431);
        // And a header just under the limit is fine.
        assertThat(b.get("/login", "X-Padding", "x".repeat(4000)).status()).isEqualTo(200);
    }

    @Test
    void the_guard_leaves_requests_without_a_body_alone() {
        assertThat(browser().get("/health").status()).isEqualTo(200);
        assertThat(padding(10)).hasSize(10);
    }
}
