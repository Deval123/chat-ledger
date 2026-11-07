package dev.chatledger.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Makes disabling a user, disabling a workspace, or changing a role take effect quickly without a
 * database query on every request: once per recheck interval (60 seconds by default) per session, the
 * signed-in user is read again. A user who is gone or disabled loses the session; a changed role replaces
 * the permissions in it.
 *
 * <p>It fails closed: if the database cannot be read the exception propagates and the request fails.
 */
public class UserStatusFilter extends OncePerRequestFilter {

    private final Clock clock;
    private final SecurityProperties properties;
    private final AppUserRepository users;
    private final SecurityContextRepository contextRepository;
    private final SecurityEvents events;

    public UserStatusFilter(Clock clock, SecurityProperties properties, AppUserRepository users,
                            SecurityContextRepository contextRepository, SecurityEvents events) {
        this.clock = clock;
        this.properties = properties;
        this.users = users;
        this.contextRepository = contextRepository;
        this.events = events;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        if (session != null && authentication != null && authentication.getPrincipal() instanceof TenantUserDetails principal) {
            Instant now = clock.instant();
            Object checkedAt = session.getAttribute(SessionAttributes.STATUS_CHECKED_AT);
            boolean due = !(checkedAt instanceof Instant last)
                    || !now.isBefore(last.plus(properties.userRecheckInterval()));
            if (due) {
                Optional<AppUser> current = users.findById(principal.tenantId(), principal.userId());
                if (current.isEmpty() || !current.get().enabled()) {
                    events.sessionEnded(current.isEmpty() ? "user-gone" : "user-disabled",
                            principal.userId(), principal.tenantId());
                    SecurityContextHolder.clearContext();
                    session.invalidate();
                } else {
                    session.setAttribute(SessionAttributes.STATUS_CHECKED_AT, now);
                    if (current.get().role() != principal.role()) {
                        var refreshed = WorkspaceAuthenticationToken.signedIn(
                                new TenantUserDetails(principal.userId(), principal.tenantId(), current.get().role()));
                        SecurityContext context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(refreshed);
                        SecurityContextHolder.setContext(context);
                        contextRepository.saveContext(context, request, response);
                    }
                }
            }
        }
        chain.doFilter(request, response);
    }
}
