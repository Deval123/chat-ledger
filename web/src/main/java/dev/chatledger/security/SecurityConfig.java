package dev.chatledger.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HeaderWriterLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ClearSiteDataHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.savedrequest.NullRequestCache;

import java.time.Clock;
import java.util.List;

import static org.springframework.security.web.header.writers.ClearSiteDataHeaderWriter.Directive.CACHE;
import static org.springframework.security.web.header.writers.ClearSiteDataHeaderWriter.Directive.COOKIES;
import static org.springframework.security.web.header.writers.ClearSiteDataHeaderWriter.Directive.STORAGE;

/**
 * The web security rules.
 *
 * <ul>
 *   <li>Deny by default: only the listed public paths are open, {@code /admin/**} needs SELLER, {@code /app/**}
 *       needs a signed-in user, and everything else is refused.</li>
 *   <li>Login is a controller, so the whole sign-in is one readable method; this class supplies the
 *       pieces it uses (authentication manager, session fixation and CSRF rotation, context
 *       repository).</li>
 *   <li>CSRF protection stays on for every unsafe method, login and logout included.</li>
 *   <li>Logout is {@code POST /logout} only; there is no GET logout.</li>
 *   <li>No saved request: after login the user always lands on {@code /app}, so there is no
 *       redirect target to tamper with.</li>
 * </ul>
 *
 * <p>Security headers (CSP, framing, referrer, cross-origin, permissions, HSTS on HTTPS) are set here, and the
 * request guard and rate limit filters sit in front of CSRF.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /** No inline code anywhere (a template lint enforces it), so nothing needs 'unsafe-inline'. */
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self'; font-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'; "
            + "object-src 'none'; frame-ancestors 'none'";

    static final String PERMISSIONS_POLICY = "accelerometer=(), camera=(), geolocation=(), gyroscope=(), "
            + "magnetometer=(), microphone=(), payment=(), usb=(), interest-cohort=()";

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** In-memory limits, bounded to 100,000 keys per rule, idle keys expiring after an hour. */
    @Bean
    public RateLimiter rateLimiter(Clock clock, SecurityProperties properties) {
        SecurityProperties.Limits l = properties.limits();
        return new InMemoryRateLimiter(clock, java.util.Map.of(
                RateLimits.LOGIN_IP, RateRule.parse("login-per-ip", l.loginPerIp()),
                RateLimits.LOGIN_ACCOUNT, RateRule.parse("login-per-account", l.loginPerAccount()),
                RateLimits.LOGIN_PAGE_IP, RateRule.parse("login-page-per-ip", l.loginPagePerIp()),
                RateLimits.INGESTION_TENANT, RateRule.parse("ingestion-per-tenant", l.ingestionPerTenant())),
                100_000, java.time.Duration.ofHours(1));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordHashing.create();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    /** Session fixation protection (new session id) plus a fresh CSRF token, at sign-in. */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    @Bean
    public AuthenticationManager authenticationManager(LoginAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            Clock clock,
            SecurityProperties properties,
            AppUserRepository users,
            AuditLogRepository audit,
            SecurityContextRepository contextRepository,
            CsrfTokenRepository csrfTokenRepository,
            SecurityEvents events,
            RateLimits rateLimits,
            @Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName,
            @Value("${server.servlet.session.cookie.secure:true}") boolean sessionCookieSecure) throws Exception {

        LogoutHandler auditLogout = (request, response, authentication) -> {
            if (authentication != null && authentication.getPrincipal() instanceof TenantUserDetails user) {
                events.loggedOut(user.userId(), user.tenantId());
                try {
                    audit.append(user.tenantId(), user.userId(), "LOGOUT", "session");
                } catch (RuntimeException auditFailure) {
                    // Signing out must never be blocked by the audit trail; the event is in the security log.
                    events.auditWriteFailed("LOGOUT");
                }
            }
        };

        RequestGuardFilter guard = new RequestGuardFilter(properties.size(), events);
        RateLimitFilter rateFilter = new RateLimitFilter(rateLimits, events);

        http
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .frameOptions(frame -> frame.deny())
                .crossOriginOpenerPolicy(coop -> coop.policy(CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN))
                .crossOriginResourcePolicy(corp -> corp.policy(CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN))
                .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
                // Sent only on HTTPS requests; behind a TLS-terminating proxy it stays off until deployment
                // names the proxy (task 13). No preload: it is hard to undo.
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000).preload(false)))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/health", "/actuator/health", "/login", "/error", "/").permitAll()
                .requestMatchers("/admin/**").hasRole("SELLER")
                .requestMatchers("/app/**").authenticated()
                .anyRequest().denyAll())
            .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
            .securityContext(context -> context.securityContextRepository(contextRepository))
            .requestCache(cache -> cache.requestCache(new NullRequestCache()))
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint(new LoginEntryPoint())
                .accessDeniedHandler(new ForbiddenHandler(events)))
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(false)      // done by SessionEndingLogoutHandler, which does not log the id
                .clearAuthentication(true)
                .addLogoutHandler(new HeaderWriterLogoutHandler(new ClearSiteDataHeaderWriter(CACHE, COOKIES, STORAGE)))
                .addLogoutHandler(auditLogout)
                .addLogoutHandler(new SessionEndingLogoutHandler(sessionCookieName, sessionCookieSecure)))
            // Size, encoding and rate limits run before anything reads the body (the CSRF filter reads a form field).
            .addFilterAfter(guard, HeaderWriterFilter.class)
            .addFilterAfter(rateFilter, RequestGuardFilter.class)
            .addFilterBefore(new SessionLifetimeFilter(clock, properties, events), SecurityContextHolderFilter.class)
            .addFilterAfter(new UserStatusFilter(clock, properties, users, contextRepository, events),
                    SecurityContextHolderFilter.class);

        return http.build();
    }
}
