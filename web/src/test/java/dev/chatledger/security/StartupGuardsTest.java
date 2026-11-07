package dev.chatledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The application refuses to start with a weakened session cookie, except in the dev profile. Task 3.4. */
class StartupGuardsTest {

    private static MockEnvironment good() {
        return new MockEnvironment()
                .withProperty("server.servlet.session.cookie.name", "__Host-chatledger-session")
                .withProperty("server.servlet.session.cookie.secure", "true")
                .withProperty("server.servlet.session.cookie.http-only", "true")
                .withProperty("server.servlet.session.cookie.same-site", "strict")
                .withProperty("server.servlet.session.cookie.path", "/");
    }

    private static void check(MockEnvironment env) {
        new StartupGuards(env).checkSessionCookie();
    }

    @Test
    void the_production_settings_pass() {
        assertThatCode(() -> check(good())).doesNotThrowAnyException();
    }

    @Test
    void each_weakening_is_refused() {
        assertThatThrownBy(() -> check(good().withProperty("server.servlet.session.cookie.secure", "false")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Secure");
        assertThatThrownBy(() -> check(good().withProperty("server.servlet.session.cookie.http-only", "false")))
                .hasMessageContaining("HttpOnly");
        assertThatThrownBy(() -> check(good().withProperty("server.servlet.session.cookie.same-site", "lax")))
                .hasMessageContaining("SameSite");
        assertThatThrownBy(() -> check(good().withProperty("server.servlet.session.cookie.same-site", "none")))
                .hasMessageContaining("SameSite");
        assertThatThrownBy(() -> check(good().withProperty("server.servlet.session.cookie.path", "/app")))
                .hasMessageContaining("__Host-");
        assertThatThrownBy(() -> check(good().withProperty("server.servlet.session.cookie.domain", "example.test")))
                .hasMessageContaining("__Host-");
    }

    @Test
    void missing_settings_count_as_weak() {
        assertThatThrownBy(() -> check(new MockEnvironment())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void the_dev_profile_may_drop_secure_only() {
        MockEnvironment dev = good().withProperty("server.servlet.session.cookie.secure", "false")
                .withProperty("server.servlet.session.cookie.name", "chatledger-dev-session");
        dev.setActiveProfiles("dev");
        assertThatCode(() -> check(dev)).doesNotThrowAnyException();

        MockEnvironment devLax = good().withProperty("server.servlet.session.cookie.same-site", "lax");
        devLax.setActiveProfiles("dev");
        assertThatThrownBy(() -> check(devLax)).hasMessageContaining("SameSite");

        MockEnvironment devHostWithoutSecure = good().withProperty("server.servlet.session.cookie.secure", "false");
        devHostWithoutSecure.setActiveProfiles("dev");
        assertThatThrownBy(() -> check(devHostWithoutSecure)).hasMessageContaining("__Host-");
    }
}
