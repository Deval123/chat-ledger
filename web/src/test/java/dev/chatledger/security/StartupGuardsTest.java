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

    @Test
    void the_number_of_trusted_proxies_is_named_in_the_startup_log() {
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("security.startup");
        logger.addAppender(logs);
        try {
            new StartupGuards(good().withProperty("app.security.trusted-proxy-hops", "1")).logTrustedProxyHops();
        } finally {
            logger.detachAppender(logs);
        }
        org.assertj.core.api.Assertions.assertThat(logs.list).singleElement().satisfies(e -> {
            org.assertj.core.api.Assertions.assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
            org.assertj.core.api.Assertions.assertThat(e.getFormattedMessage()).contains("trusted-proxy-hops=1");
        });
    }

    private static void profiles(MockEnvironment env) {
        new StartupGuards(env).checkProfiles();
    }

    private static MockEnvironment prod() {
        MockEnvironment env = good().withProperty("spring.flyway.enabled", "false").withProperty("server.forward-headers-strategy", "none");
        env.setActiveProfiles("prod");
        return env;
    }

    @Test
    void the_prod_profile_runs_without_flyway_and_without_forwarded_header_rewriting_and_never_with_dev() {
        assertThatCode(() -> profiles(prod())).doesNotThrowAnyException();
        assertThatThrownBy(() -> profiles(prod().withProperty("spring.flyway.enabled", "true"))).hasMessageContaining("flyway");
        MockEnvironment flywayDefault = good().withProperty("server.forward-headers-strategy", "none");
        flywayDefault.setActiveProfiles("prod");
        assertThatThrownBy(() -> profiles(flywayDefault)).as("Flyway is on unless switched off").hasMessageContaining("flyway");
        assertThatThrownBy(() -> profiles(prod().withProperty("server.forward-headers-strategy", "native"))).hasMessageContaining("forward-headers");
        MockEnvironment both = prod();
        both.setActiveProfiles("prod", "dev");
        assertThatThrownBy(() -> profiles(both)).hasMessageContaining("cannot be active together");
        MockEnvironment devAlways = good().withProperty("app.security.hsts", "always");
        devAlways.setActiveProfiles("dev");
        assertThatThrownBy(() -> profiles(devAlways)).hasMessageContaining("hsts");
        assertThatCode(() -> profiles(good().withProperty("app.security.hsts", "always"))).as("outside dev").doesNotThrowAnyException();
    }
}
