package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sign-in, sign-out, sessions and CSRF, against the real server and real PostgreSQL.
 *
 * <p>Requirements: 9.1, 9.4. Tasks 3.4, 3.5, 3.8 (session and CSRF part).
 */
class AuthenticationIT extends AbstractWebIT {

    @Autowired
    ApplicationContext context;

    // ── The page and the cookie ──────────────────────────────────────────────

    @Test
    void the_login_page_carries_a_csrf_token_and_a_hardened_session_cookie() {
        Browser b = browser();
        Browser.Response page = b.get("/login");
        assertThat(page.status()).isEqualTo(200);
        assertThat(Browser.csrf(page)).isNotBlank();
        assertThat(page.body()).contains("name=\"workspace\"", "name=\"email\"", "name=\"password\"");
        // A password field never has a value and tells browsers it is a password.
        assertThat(page.body()).contains("type=\"password\"");

        String cookie = page.setCookies().stream().filter(c -> c.startsWith(COOKIE + "=")).findFirst().orElseThrow();
        assertThat(cookie).contains("Secure").contains("HttpOnly").contains("SameSite=Strict").contains("Path=/")
                .doesNotContainIgnoringCase("Domain=");
    }

    @Test
    void the_session_cookie_keeps_its_flags_after_sign_in() {
        Workspace w = newWorkspace();
        Browser b = browser();
        Browser.Response r = signIn(b, w.slug(), w.email(), w.password());
        List<String> cookies = r.setCookies().stream().filter(c -> c.startsWith(COOKIE + "=")).toList();
        assertThat(cookies).hasSize(1);
        assertThat(cookies.get(0)).contains("Secure").contains("HttpOnly").contains("SameSite=Strict").contains("Path=/")
                .doesNotContainIgnoringCase("Domain=");
    }

    // ── Success and fixation ─────────────────────────────────────────────────

    @Test
    void sign_in_goes_to_the_app_replaces_the_session_id_and_the_old_id_is_dead() {
        Workspace w = newWorkspace();
        Browser b = browser();
        b.get("/login");
        String before = b.cookie(COOKIE);
        assertThat(before).isNotBlank();

        Browser.Response r = signIn(b, w.slug(), w.email(), w.password());
        assertThat(r.status()).isEqualTo(302);
        assertThat(r.header("location")).endsWith("/app");
        String after = b.cookie(COOKIE);
        assertThat(after).isNotBlank().isNotEqualTo(before);

        Browser.Response app = b.get("/app");
        assertThat(app.status()).isEqualTo(200);
        assertThat(app.body()).contains("SELLER");

        // An attacker who planted the pre-login id gets nothing.
        Browser attacker = browser();
        attacker.setCookie(COOKIE, before);
        Browser.Response denied = attacker.get("/app");
        assertThat(denied.status()).isEqualTo(302);
        assertThat(denied.header("location")).endsWith("/login");
    }

    @Test
    void the_workspace_code_and_email_are_case_and_space_insensitive() {
        Workspace w = newWorkspace();
        Browser b = browser();
        Browser.Response r = signIn(b, "  " + w.slug().toUpperCase() + " ", " " + w.email().toUpperCase() + " ", w.password());
        assertThat(r.header("location")).endsWith("/app");
    }

    @Test
    void the_same_email_in_two_workspaces_signs_into_the_one_named() {
        Workspace a = newWorkspace();
        Workspace b = newWorkspace();
        String shared = "shared-" + unique() + "@example.test";
        String passwordA = newPassword();
        String passwordB = newPassword();
        // Two users with the same email, one in each workspace, with different passwords.
        asOwner("INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (gen_random_uuid(), ?, ?, ?, 'SELLER')",
                a.tenantId(), shared, PasswordHashing.create().encode(passwordA));
        asOwner("INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (gen_random_uuid(), ?, ?, ?, 'HELPER')",
                b.tenantId(), shared, PasswordHashing.create().encode(passwordB));

        Browser inA = signedIn(a.slug(), shared, passwordA);
        assertThat(inA.get("/app/probe/whoami").body()).startsWith(a.tenantId() + " ").endsWith("SELLER");
        Browser other = signedIn(b.slug(), shared, passwordB);
        assertThat(other.get("/app/probe/whoami").body()).startsWith(b.tenantId() + " ").endsWith("HELPER");
        // A's password does not open B's account.
        assertThat(signIn(browser(), b.slug(), shared, passwordA).header("location")).endsWith("/login?error");
    }

    // ── Failures are indistinguishable ───────────────────────────────────────

    @Test
    void every_kind_of_failure_gets_the_same_response() {
        Workspace w = newWorkspace();
        Workspace disabledShop = newWorkspace();
        asOwner("UPDATE tenant SET disabled_at = now() WHERE id = ?", disabledShop.tenantId());
        Member disabledUser = addUser(w, Role.HELPER);
        asOwner("UPDATE app_user SET disabled_at = now() WHERE id = ?", disabledUser.userId());
        Workspace other = newWorkspace();

        Map<String, Browser.Response> responses = new TreeMap<>();
        responses.put("wrong password", signIn(browser(), w.slug(), w.email(), newPassword()));
        responses.put("unknown email", signIn(browser(), w.slug(), "nobody-" + unique() + "@example.test", w.password()));
        responses.put("unknown workspace", signIn(browser(), "nowhere-" + unique(), w.email(), w.password()));
        responses.put("email of another workspace", signIn(browser(), w.slug(), other.email(), other.password()));
        responses.put("disabled user", signIn(browser(), w.slug(), disabledUser.email(), disabledUser.password()));
        responses.put("disabled workspace", signIn(browser(), disabledShop.slug(), disabledShop.email(), disabledShop.password()));
        responses.put("malformed workspace", signIn(browser(), "bad workspace!", w.email(), w.password()));
        responses.put("malformed email", signIn(browser(), w.slug(), "not-an-email", w.password()));
        responses.put("empty password", signIn(browser(), w.slug(), w.email(), ""));
        responses.put("too long a password", signIn(browser(), w.slug(), w.email(), "x".repeat(PasswordPolicy.MAX_LENGTH + 1)));
        responses.put("odd characters", signIn(browser(), "\u202e", "\u00e9@\u200b", "\uD83D\uDE00"));

        Browser.Response reference = responses.get("wrong password");
        responses.forEach((name, response) -> {
            assertThat(response.status()).as(name).isEqualTo(302);
            assertThat(response.header("location")).as(name).endsWith("/login?error");
            assertThat(response.body()).as(name).isEqualTo(reference.body());
            assertThat(describe(response.headers())).as(name).isEqualTo(describe(reference.headers()));
        });
    }

    @Test
    void control_characters_and_bad_encodings_in_the_form_are_rejected_not_repaired() {
        Workspace w = newWorkspace();
        Browser b = browser();
        String token = Browser.csrf(b.get("/login"));
        String good = "_csrf=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8)
                + "&workspace=" + w.slug() + "&email=" + java.net.URLEncoder.encode(w.email(), java.nio.charset.StandardCharsets.UTF_8)
                + "&password=";
        for (String badPassword : new String[]{"abc%00def", "abc%1Bdef", "%FF%FE", "abc%C3", "abc%zz", "abc%"}) {
            Browser.Response r = b.postRaw("/login", "application/x-www-form-urlencoded",
                    (good + badPassword).getBytes(java.nio.charset.StandardCharsets.US_ASCII), false);
            assertThat(r.status()).as(badPassword).isEqualTo(400);
            assertThat(r.body()).as(badPassword).isEqualTo("Bad request.");
        }
        // The control: the same form with a good password field is understood (it fails sign-in, not decoding).
        Browser.Response ok = b.postRaw("/login", "application/x-www-form-urlencoded",
                (good + "not-the-password").getBytes(java.nio.charset.StandardCharsets.US_ASCII), false);
        assertThat(ok.status()).isEqualTo(302);
        assertThat(ok.header("location")).endsWith("/login?error");
    }

    /** Header names and values that should be identical, leaving out what is meant to differ (date, ids). */
    private static Map<String, List<String>> describe(HttpHeaders headers) {
        Map<String, List<String>> out = new TreeMap<>();
        headers.map().forEach((name, values) -> {
            String lower = name.toLowerCase();
            if (lower.equals("date")) {
                return;
            }
            if (lower.equals("set-cookie")) {
                // Same attributes, different id: compare everything after the value.
                out.put(lower, values.stream().map(v -> v.substring(v.indexOf(';') + 1).replaceAll("Expires=[^;]*", "")).toList());
                return;
            }
            out.put(lower, new ArrayList<>(values));
        });
        return out;
    }

    @Test
    void a_failed_sign_in_creates_no_signed_in_session_and_writes_no_audit_row() {
        Workspace w = newWorkspace();
        Browser b = browser();
        signIn(b, w.slug(), w.email(), newPassword());
        Browser.Response app = b.get("/app");
        assertThat(app.status()).isEqualTo(302);
        assertThat(app.header("location")).endsWith("/login");
        assertThat(audit.recent(w.tenantId(), 10)).isEmpty();
    }

    // ── The tenant comes from the database, never from the request ──────────

    @Test
    void a_tenant_named_in_the_request_is_ignored() {
        Workspace mine = newWorkspace();
        Workspace theirs = newWorkspace();
        Browser b = browser();
        Browser.Response page = b.get("/login");
        Browser.Response r = b.post("/login", Map.of(
                "_csrf", Browser.csrf(page), "workspace", mine.slug(), "email", mine.email(), "password", mine.password(),
                "tenantId", theirs.tenantId().toString(), "tenant_id", theirs.tenantId().toString(),
                "tenant", theirs.slug()),
                "X-Tenant-Id", theirs.tenantId().toString(), "X-Tenant", theirs.slug());
        assertThat(r.header("location")).endsWith("/app");

        String who = b.get("/app/probe/whoami", "X-Tenant-Id", theirs.tenantId().toString()).body();
        assertThat(who).startsWith(mine.tenantId().toString()).doesNotContain(theirs.tenantId().toString());
        // The sign-in is on the right tenant's audit trail only.
        assertThat(audit.recent(mine.tenantId(), 10)).extracting(AuditLogRepository.Entry::action).containsExactly("LOGIN_SUCCESS");
        assertThat(audit.recent(theirs.tenantId(), 10)).isEmpty();
    }

    @Test
    void the_audit_trail_records_sign_in_and_sign_out_with_the_user() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        Browser.Response app = b.get("/app");
        b.post("/logout", Map.of("_csrf", Browser.csrf(app)));

        var entries = audit.recent(w.tenantId(), 10);
        assertThat(entries).extracting(AuditLogRepository.Entry::action).containsExactlyInAnyOrder("LOGIN_SUCCESS", "LOGOUT");
        assertThat(entries).extracting(AuditLogRepository.Entry::actor).containsOnly(w.sellerId());
    }

    @Test
    void sign_in_fails_closed_when_the_audit_trail_cannot_be_written() {
        Workspace w = newWorkspace();
        // Make writing this tenant's audit rows fail, as a full disk or a broken table would.
        String constraint = "block_" + unique();
        asOwner("ALTER TABLE audit_log ADD CONSTRAINT " + constraint
                + " CHECK (tenant_id <> '" + w.tenantId() + "'::uuid) NOT VALID");
        Browser b = browser();
        Browser.Response r = signIn(b, w.slug(), w.email(), w.password());

        assertThat(r.status()).isEqualTo(500);
        assertThat(r.body()).doesNotContain(constraint).doesNotContain("audit_log").doesNotContain(w.email());
        assertThat(r.header("location")).isNull();
        Browser.Response app = b.get("/app");
        assertThat(app.status()).as("no signed-in session was created").isEqualTo(302);
        assertThat(app.header("location")).endsWith("/login");
        assertThat(audit.recent(w.tenantId(), 10)).isEmpty();
    }

    // ── CSRF ─────────────────────────────────────────────────────────────────

    @Test
    void a_post_without_a_valid_csrf_token_is_refused_everywhere() {
        Workspace w = newWorkspace();

        // Login form.
        Browser anon = browser();
        anon.get("/login");
        Browser.Response noToken = anon.post("/login", Map.of("workspace", w.slug(), "email", w.email(), "password", w.password()));
        assertThat(noToken.status()).isEqualTo(403);
        assertThat(noToken.body()).isEqualTo("Forbidden");
        Browser.Response wrongToken = anon.post("/login", Map.of("_csrf", "not-the-token", "workspace", w.slug(),
                "email", w.email(), "password", w.password()));
        assertThat(wrongToken.status()).isEqualTo(403);
        assertThat(anon.get("/app").status()).isEqualTo(302);

        // Signed in: logout and an htmx-style request.
        Browser b = signedIn(w.slug(), w.email(), w.password());
        assertThat(b.post("/logout", Map.of()).status()).isEqualTo(403);
        assertThat(b.post("/app/probe/record", Map.of(), "HX-Request", "true").status()).isEqualTo(403);
        assertThat(b.get("/app").status()).as("still signed in after the refused logout").isEqualTo(200);

        // The token in the header (what htmx sends) is accepted.
        String token = Browser.csrf(b.get("/app"));
        assertThat(b.post("/app/probe/record", Map.of(), "HX-Request", "true", "X-CSRF-TOKEN", token).status()).isEqualTo(200);
    }

    @Test
    void the_token_from_before_sign_in_stops_working_after_it() {
        Workspace w = newWorkspace();
        Browser b = browser();
        String before = Browser.csrf(b.get("/login"));
        b.post("/login", Map.of("_csrf", before, "workspace", w.slug(), "email", w.email(), "password", w.password()));
        assertThat(b.get("/app").status()).isEqualTo(200);

        assertThat(b.post("/app/probe/record", Map.of("_csrf", before)).status()).isEqualTo(403);
        String after = Browser.csrf(b.get("/app"));
        assertThat(after).isNotEqualTo(before);
        assertThat(b.post("/app/probe/record", Map.of("_csrf", after)).status()).isEqualTo(200);
    }

    @Test
    void a_csrf_token_from_another_session_is_refused() {
        Workspace w = newWorkspace();
        Browser other = signedIn(w.slug(), w.email(), w.password());
        String otherToken = Browser.csrf(other.get("/app"));
        Browser mine = signedIn(w.slug(), w.email(), w.password());
        assertThat(mine.post("/app/probe/record", Map.of("_csrf", otherToken)).status()).isEqualTo(403);
    }

    // ── Logout ───────────────────────────────────────────────────────────────

    @Test
    void logout_is_post_only_and_ends_the_session() {
        Workspace w = newWorkspace();
        Browser b = signedIn(w.slug(), w.email(), w.password());
        String oldCookie = b.cookie(COOKIE);

        // GET /logout does nothing: the session survives.
        assertThat(b.get("/logout").status()).isIn(403, 404, 405);
        assertThat(b.get("/app").status()).isEqualTo(200);

        Browser.Response out = b.post("/logout", Map.of("_csrf", Browser.csrf(b.get("/app"))));
        assertThat(out.status()).isEqualTo(302);
        assertThat(out.header("location")).endsWith("/login?logout");
        // The deletion carries the same flags the cookie was issued with; a browser ignores a __Host- deletion without Secure.
        assertThat(out.setCookies()).anyMatch(c -> c.startsWith(COOKIE + "=;") && c.contains("Max-Age=0")
                && c.contains("Secure") && c.contains("HttpOnly") && c.contains("SameSite=Strict") && c.contains("Path=/"));
        assertThat(b.cookie(COOKIE)).isNull();

        Browser replay = browser();
        replay.setCookie(COOKIE, oldCookie);
        assertThat(replay.get("/app").status()).as("the old session id is dead server-side").isEqualTo(302);
    }

    // ── Unauthenticated access ───────────────────────────────────────────────

    @Test
    void unauthenticated_requests_go_to_the_login_page_and_htmx_requests_get_401_with_a_redirect_header() {
        Browser b = browser();
        for (String path : new String[]{"/app", "/app/probe/whoami", "/admin/probe/export", "/admin/anything"}) {
            Browser.Response r = b.get(path);
            assertThat(r.status()).as(path).isEqualTo(302);
            assertThat(r.header("location")).as(path).endsWith("/login");
        }
        Browser.Response htmx = b.get("/app/probe/whoami", "HX-Request", "true");
        assertThat(htmx.status()).isEqualTo(401);
        assertThat(htmx.header("hx-redirect")).isEqualTo("/login");
        assertThat(htmx.body()).doesNotContain("<form");
    }

    @Test
    void unlisted_paths_are_denied_by_default() {
        Browser b = browser();
        assertThat(b.get("/not-listed").status()).isIn(302, 403);
        Workspace w = newWorkspace();
        Browser seller = signedIn(w.slug(), w.email(), w.password());
        assertThat(seller.get("/not-listed").status()).isEqualTo(403);
        assertThat(seller.get("/actuator/env").status()).isEqualTo(403);
        assertThat(seller.get("/register").status()).isEqualTo(403);
        assertThat(seller.post("/register", Map.of("_csrf", Browser.csrf(seller.get("/app")))).status()).isEqualTo(403);
    }

    @Test
    void the_public_paths_stay_public() {
        Browser b = browser();
        assertThat(b.get("/health").status()).isEqualTo(200);
        assertThat(b.get("/login").status()).isEqualTo(200);
        assertThat(b.get("/").status()).isEqualTo(302);
    }

    // ── Logs and framework defaults ──────────────────────────────────────────

    @Test
    void no_generated_default_user_exists() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void the_log_holds_no_password_hash_session_id_csrf_token_or_email() {
        Workspace w = newWorkspace();
        Member helper = addUser(w, Role.HELPER);
        String wrongPassword = newPassword();

        Browser failing = browser();
        Browser.Response page = failing.get("/login");
        String csrf = Browser.csrf(page);
        signIn(failing, w.slug(), helper.email(), wrongPassword);
        failing.post("/login", Map.of("workspace", w.slug(), "email", helper.email(), "password", wrongPassword)); // CSRF refusal

        Browser b = signedIn(w.slug(), helper.email(), helper.password());
        String sessionId = b.cookie(COOKIE);
        String token = Browser.csrf(b.get("/app"));
        b.get("/admin/probe/export");                                   // access denied
        b.post("/app/probe/record", Map.of("_csrf", "bad-" + UUID.randomUUID())); // CSRF refusal
        b.post("/logout", Map.of("_csrf", token));

        String log = loggedText();
        assertThat(log).as("the log is not empty, so this check is not vacuous").contains("login succeeded", "login failed");
        for (String secret : new String[]{wrongPassword, helper.password(), w.password(), helper.email(), w.email(),
                w.slug(), sessionId, token, csrf, "argon2"}) {
            assertThat(log).as("log must not contain " + secret.substring(0, Math.min(6, secret.length())) + "...")
                    .doesNotContain(secret);
        }
    }
}
