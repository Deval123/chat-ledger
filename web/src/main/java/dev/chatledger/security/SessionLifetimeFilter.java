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
 * simply no session. A demo visitor's session also ends when its lease does ({@link SessionAttributes#ENDS_AT}, task 9).
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
            Object endsAt = session.getAttribute(SessionAttributes.ENDS_AT);
            Instant now = clock.instant();
            if (since instanceof Instant signedInAt && now.isAfter(signedInAt.plus(properties.session().absoluteTimeout()))) {
                events.sessionEnded("absolute-timeout", null, null);
                session.invalidate();
            } else if (endsAt instanceof Instant leaseEnd && !now.isBefore(leaseEnd)) {
                // A demo lease (task 9): the session ends with it even if the refresh job has not retired the workspace yet.
                events.sessionEnded("demo-lease-ended", null, null);
                session.invalidate();
            }
        }
        chain.doFilter(request, response);
    }
}
