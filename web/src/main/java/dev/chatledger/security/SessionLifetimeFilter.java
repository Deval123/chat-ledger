package dev.chatledger.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

/**
 * Enforces the absolute session lifetime. The servlet container only has an idle timeout, so a
 * session that stays active could live forever; this ends it a fixed time after sign-in
 * whatever the activity. It runs before the security context is loaded, so an expired session is
 * simply no session.
 */
public class SessionLifetimeFilter extends OncePerRequestFilter {

    private final Clock clock;
    private final SecurityProperties properties;
    private final SecurityEvents events;

    public SessionLifetimeFilter(Clock clock, SecurityProperties properties, SecurityEvents events) {
        this.clock = clock;
        this.properties = properties;
        this.events = events;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object since = session.getAttribute(SessionAttributes.AUTHENTICATED_AT);
            if (since instanceof Instant signedInAt
                    && clock.instant().isAfter(signedInAt.plus(properties.session().absoluteTimeout()))) {
                events.sessionEnded("absolute-timeout", null, null);
                session.invalidate();
            }
        }
        chain.doFilter(request, response);
    }
}
