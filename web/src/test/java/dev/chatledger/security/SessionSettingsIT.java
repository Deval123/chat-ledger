package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Settings that need a look inside the session: the idle timeouts before and after sign-in, and the
 * Clear-Site-Data header on sign-out. (Clear-Site-Data is only sent on secure requests, so the test
 * marks its requests secure.)
 *
 * <p>Requirements: 9.1, 9.4. Task 3.5.
 */
@AutoConfigureMockMvc
class SessionSettingsIT extends AbstractWebIT {

    @Autowired
    MockMvc mvc;

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(html);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void the_session_of_the_login_page_is_short_and_the_signed_in_session_is_thirty_minutes() throws Exception {
        Workspace w = newWorkspace();
        MvcResult page = mvc.perform(get("/login").secure(true)).andReturn();
        MockHttpSession session = (MockHttpSession) page.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getMaxInactiveInterval()).as("before sign-in").isEqualTo(600);

        MvcResult login = mvc.perform(post("/login").secure(true).session(session)
                .param("_csrf", csrf(page.getResponse().getContentAsString()))
                .param("workspace", w.slug()).param("email", w.email()).param("password", w.password())).andReturn();
        assertThat(login.getResponse().getRedirectedUrl()).isEqualTo("/app");
        MockHttpSession signedIn = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(signedIn.getMaxInactiveInterval()).as("after sign-in").isEqualTo(1800);
    }

    @Test
    void sign_out_asks_the_browser_to_clear_cookies_storage_and_cache() throws Exception {
        Workspace w = newWorkspace();
        MvcResult page = mvc.perform(get("/login").secure(true)).andReturn();
        MockHttpSession session = (MockHttpSession) page.getRequest().getSession(false);
        MvcResult login = mvc.perform(post("/login").secure(true).session(session)
                .param("_csrf", csrf(page.getResponse().getContentAsString()))
                .param("workspace", w.slug()).param("email", w.email()).param("password", w.password())).andReturn();
        MockHttpSession signedIn = (MockHttpSession) login.getRequest().getSession(false);
        MvcResult app = mvc.perform(get("/app").secure(true).session(signedIn)).andReturn();
        assertThat(app.getResponse().getStatus()).isEqualTo(200);

        MvcResult out = mvc.perform(post("/logout").secure(true).session(signedIn)
                .param("_csrf", csrf(app.getResponse().getContentAsString()))).andReturn();
        assertThat(out.getResponse().getStatus()).isEqualTo(302);
        assertThat(out.getResponse().getHeader("Clear-Site-Data")).contains("cache").contains("cookies").contains("storage");
    }
}
