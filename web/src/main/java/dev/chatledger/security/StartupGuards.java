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

    /**
     * The language of the pages (task 12a, D4): English until the French file is complete, so no visitor ever sees a page that is half
     * French. Refuses (Boot's defaults count as set, because both are the unsafe ones):
     * <ul>
     *   <li>{@code spring.messages.fallback-to-system-locale} other than {@code false}: with it, a JVM whose locale is French serves
     *       {@code messages_fr.properties} on English pages;</li>
     *   <li>a locale resolver other than {@code fixed} ({@code accept-header} is Boot's default), or a fixed locale other than
     *       {@code en}, while {@code messages_fr.properties} lacks any key of {@code messages.properties}.</li>
     * </ul>
     */
    @PostConstruct
    void checkLocale() {
        if (!"false".equalsIgnoreCase(environment.getProperty("spring.messages.fallback-to-system-locale", "true").trim())) {
            throw new IllegalStateException("spring.messages.fallback-to-system-locale must be false: the JVM's locale must never choose the language");
        }
        String resolver = environment.getProperty("spring.web.locale-resolver", "accept-header").trim();
        String locale = environment.getProperty("spring.web.locale", "").trim();
        boolean english = "fixed".equalsIgnoreCase(resolver) && "en".equals(locale);
        if (!english) {
            int missing = missingKeys("/messages.properties", "/messages_fr.properties").size();
            if (missing > 0) {
                throw new IllegalStateException("spring.web.locale-resolver=" + resolver + ", spring.web.locale=" + locale
                        + " is refused: messages_fr.properties lacks " + missing + " keys of messages.properties (keep fixed and en until it has all)");
            }
        }
    }

    /** The keys of the {@code base} bundle that {@code other} lacks; every key if {@code other} does not exist. Both read as UTF-8. */
    static java.util.Set<String> missingKeys(String base, String other) {
        java.util.Set<String> keys = new java.util.TreeSet<>(load(base).stringPropertyNames());
        if (keys.isEmpty()) {
            throw new IllegalStateException(base + " is missing or empty");
        }
        keys.removeAll(load(other).stringPropertyNames());
        return keys;
    }

    private static java.util.Properties load(String resource) {
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = StartupGuards.class.getResourceAsStream(resource)) {
            if (in != null) {
                p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return p;
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
