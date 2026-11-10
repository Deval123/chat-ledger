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

    /**
     * Names the number of trusted proxies in the startup log (task 9, D23). The value itself is bounded by {@link SecurityProperties},
     * which stops the application on anything outside 0 to 2.
     */
    @PostConstruct
    void logTrustedProxyHops() {
        org.slf4j.LoggerFactory.getLogger("security.startup")
                .info(describeTrustedProxyHops(environment.getProperty("app.security.trusted-proxy-hops", Integer.class, 0)));
    }

    static String describeTrustedProxyHops(int hops) {
        return hops == 0
                ? "app.security.trusted-proxy-hops=0: the client address is the socket address; X-Forwarded-For is ignored"
                : "app.security.trusted-proxy-hops=" + hops + ": the client address is the X-Forwarded-For entry " + hops
                        + " from the right; the socket address if the header has fewer entries or a value that is not an address";
    }

    /**
     * The production profile (task 13): never together with {@code dev}; Flyway off (the web process does not hold the schema owner's
     * login: migrations run in their own process); no forwarded-header rewriting by Tomcat (only {@code ClientAddress} reads
     * {@code X-Forwarded-For}, at the configured hops). And {@code app.security.hsts=always} is refused under {@code dev}, which serves
     * plain HTTP on localhost.
     */
    @PostConstruct
    void checkProfiles() {
        boolean dev = environment.acceptsProfiles(Profiles.of("dev"));
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        if (dev && prod) {
            throw new IllegalStateException("The dev and prod profiles cannot be active together");
        }
        if (dev && "always".equalsIgnoreCase(environment.getProperty("app.security.hsts", "if-secure"))) {
            throw new IllegalStateException("app.security.hsts=always is refused under the dev profile");
        }
        if (prod) {
            if (environment.getProperty("spring.flyway.enabled", Boolean.class, true)) {
                throw new IllegalStateException("The prod profile runs with spring.flyway.enabled=false (migrations run in db-migrate)");
            }
            if (!"none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", ""))) {
                throw new IllegalStateException("The prod profile needs server.forward-headers-strategy=none");
            }
        }
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
