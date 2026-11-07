package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Every kind of response carries the security headers, and the server names itself nowhere. HSTS
 * appears on HTTPS requests only.
 *
 * <p>Requirement: 9.1. Task 3.8.
 */
@AutoConfigureMockMvc
class SecurityHeadersIT extends AbstractWebIT {

    @Autowired
    MockMvc mvc;

    @Test
    void headers_are_on_pages_redirects_refusals_errors_and_machine_endpoints() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        Browser anon = browser();
        assertSecurityHeaders(anon.get("/login"), "login page");
        assertSecurityHeaders(anon.get("/health"), "health");
        assertSecurityHeaders(anon.get("/app"), "redirect to login");
        assertSecurityHeaders(anon.get("/app", "HX-Request", "true"), "htmx 401");
        assertSecurityHeaders(anon.post("/login", Map.of("workspace", "x")), "CSRF refusal");
        assertSecurityHeaders(anon.get("/nothing-here"), "unlisted path");

        Browser b = signedIn(w.slug(), helper.email(), helper.password());
        assertSecurityHeaders(b.get("/app"), "app page");
        assertSecurityHeaders(b.get("/admin/probe/export"), "403");
        assertSecurityHeaders(b.get("/app/no-such-page"), "404 error page");
        assertSecurityHeaders(b.postRaw("/login", "application/x-www-form-urlencoded", new byte[5000], false), "413");
        assertSecurityHeaders(b.get("/login?a=%FF"), "400");
    }

    @Test
    void the_policy_blocks_framing_and_all_inline_or_foreign_code() {
        Browser.Response r = browser().get("/login");
        String csp = r.header("content-security-policy");
        assertThat(csp).doesNotContain("unsafe-inline").doesNotContain("unsafe-eval").doesNotContain("*")
                .doesNotContain("http:").doesNotContain("https:").contains("default-src 'none'")
                .contains("frame-ancestors 'none'").contains("base-uri 'none'").contains("object-src 'none'")
                .contains("form-action 'self'");
    }

    @Test
    void strict_transport_security_is_sent_over_https_only_without_preload() throws Exception {
        MvcResult https = mvc.perform(get("/login").secure(true)).andReturn();
        String hsts = https.getResponse().getHeader("Strict-Transport-Security");
        assertThat(hsts).contains("max-age=31536000").contains("includeSubDomains").doesNotContain("preload");

        MvcResult http = mvc.perform(get("/login")).andReturn();
        assertThat(http.getResponse().getHeader("Strict-Transport-Security")).isNull();
        assertThat(new String(http.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)).contains("Sign in");
    }
}
