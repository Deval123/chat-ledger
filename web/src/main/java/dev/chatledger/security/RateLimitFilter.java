package dev.chatledger.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Limits by client address on the sign-in page (every load creates an anonymous session), on sign-in attempts, and on the demo's
 * public page and its claim (task 9). It sits before the CSRF check on purpose: an attacker does not get free attempts by omitting the
 * token. The per-account limit is applied in the login controller, where the submitted account is known.
 *
 * <p>The address is the socket address unless {@code app.security.trusted-proxy-hops} names the proxies in front ({@link ClientAddress});
 * trusting forwarded headers blindly would let anyone choose the key and walk past the limit.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimits limits;
    private final SecurityEvents events;
    private final int trustedProxyHops;
    private final boolean demoEnabled;

    public RateLimitFilter(RateLimits limits, SecurityEvents events, int trustedProxyHops, boolean demoEnabled) {
        this.limits = limits;
        this.events = events;
        this.trustedProxyHops = trustedProxyHops;
        this.demoEnabled = demoEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        RateDecision decision = RateDecision.permit();
        String rule = null;
        if ("/login".equals(path)) {
            String address = ClientAddress.of(request, trustedProxyHops);
            rule = "login-address";
            decision = switch (request.getMethod()) {
                case "POST" -> limits.loginFromAddress(address);
                case "GET" -> limits.loginPageFromAddress(address);
                default -> RateDecision.permit();
            };
            if (!decision.allowed()) {
                events.rateLimited(rule, address);
            }
        } else if (demoEnabled && "/demo".equals(path)) {
            // Every request to the demo page, before CSRF. The claim itself has its own limits, spent only when a workspace is
            // actually asked for (a visitor who is already in one is sent back to it and spends none; task 9, D22).
            String address = ClientAddress.of(request, trustedProxyHops);
            rule = "demo-address";
            decision = limits.demoPageFromAddress(address);
            if (!decision.allowed()) {
                events.rateLimited(rule, address);
            }
        }
        if (!decision.allowed()) {
            PlainResponses.tooManyRequests(response, decision.retryAfterSeconds());
            return;
        }
        chain.doFilter(request, response);
    }
}
