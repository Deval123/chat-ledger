package dev.chatledger.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Limits by client address on the sign-in page (every load creates an anonymous session) and on
 * sign-in attempts. It sits before the CSRF check on purpose: an attacker does not get free attempts
 * by omitting the token. The per-account limit is applied in the login controller, where the submitted
 * account is known.
 *
 * <p>The address is the socket address. Forwarded headers are not trusted here (they are trusted
 * only once deployment names its proxy, task 13); trusting them blindly would let anyone choose the
 * key and walk past the limit.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimits limits;
    private final SecurityEvents events;

    public RateLimitFilter(RateLimits limits, SecurityEvents events) {
        this.limits = limits;
        this.events = events;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("/login".equals(request.getServletPath())) {
            String address = request.getRemoteAddr();
            RateDecision decision = switch (request.getMethod()) {
                case "POST" -> limits.loginFromAddress(address);
                case "GET" -> limits.loginPageFromAddress(address);
                default -> RateDecision.permit();
            };
            if (!decision.allowed()) {
                events.rateLimited("login-address", address);
                PlainResponses.tooManyRequests(response, decision.retryAfterSeconds());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
