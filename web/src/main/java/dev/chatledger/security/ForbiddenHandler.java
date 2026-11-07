package dev.chatledger.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Answers 403 with the same short body whatever the reason (a missing CSRF token, a forbidden action).
 * The body says nothing about why, and the reason goes only to the security log.
 */
public class ForbiddenHandler implements AccessDeniedHandler {

    private final SecurityEvents events;

    public ForbiddenHandler(SecurityEvents events) {
        this.events = events;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        if (exception instanceof CsrfException) {
            events.csrfRejected(request.getRequestURI());
        } else {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            var user = authentication != null && authentication.getPrincipal() instanceof TenantUserDetails p
                    ? p.userId() : null;
            events.accessDenied(user, request.getRequestURI());
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("Forbidden");
    }
}
