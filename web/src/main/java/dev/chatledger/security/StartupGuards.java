package dev.chatledger.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import jakarta.annotation.PostConstruct;

/**
 * Refuses to start with a weakened session cookie unless the {@code dev} profile is on. The
 * cookie must be {@code Secure}, {@code HttpOnly} and {@code SameSite=Strict}; a {@code __Host-} cookie also
 * needs {@code Secure} and the path {@code /}. Only the dev profile (plain-HTTP localhost) may relax
 * {@code Secure}. This keeps a development setting from reaching production by accident.
 */
@Configuration
public class StartupGuards {

    private static final String PREFIX = "server.servlet.session.cookie.";

    private final Environment environment;

    public StartupGuards(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void checkSessionCookie() {
        boolean dev = environment.acceptsProfiles(Profiles.of("dev"));
        boolean secure = environment.getProperty(PREFIX + "secure", Boolean.class, false);
        boolean httpOnly = environment.getProperty(PREFIX + "http-only", Boolean.class, false);
        String sameSite = environment.getProperty(PREFIX + "same-site", "");
        String name = environment.getProperty(PREFIX + "name", "JSESSIONID");

        if (!httpOnly) {
            throw new IllegalStateException("The session cookie must be HttpOnly (" + PREFIX + "http-only=true)");
        }
        if (!"strict".equalsIgnoreCase(sameSite)) {
            throw new IllegalStateException("The session cookie must be SameSite=Strict (" + PREFIX + "same-site=strict)");
        }
        if (!secure && !dev) {
            throw new IllegalStateException(
                    "The session cookie must be Secure outside the dev profile (" + PREFIX + "secure=true)");
        }
        if (name.startsWith("__Host-") && (!secure || !"/".equals(environment.getProperty(PREFIX + "path", "/"))
                || environment.getProperty(PREFIX + "domain") != null)) {
            throw new IllegalStateException("A __Host- cookie must be Secure, have path / and no domain");
        }
    }
}
