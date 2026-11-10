package dev.chatledger.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import java.io.IOException;

/**
 * What an unauthenticated request gets. A normal page request is redirected to the sign-in page (on the demo deployment, the demo page:
 * there is no account to sign in with there). An htmx request (it sends {@code HX-Request}) gets 401 and an {@code HX-Redirect} header
 * instead, so the page is not swapped into the middle of another page.
 */
public class LoginEntryPoint implements AuthenticationEntryPoint {

    private final String target;
    private final AuthenticationEntryPoint redirect;

    public LoginEntryPoint() {
        this("/login");
    }

    /** @param target {@code /login}, or {@code /demo} on the demo deployment */
    public LoginEntryPoint(String target) {
        if (!"/login".equals(target) && !"/demo".equals(target)) {
            throw new IllegalArgumentException("the entry point is the sign-in page or the demo page");
        }
        this.target = target;
        this.redirect = new LoginUrlAuthenticationEntryPoint(target);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, jakarta.servlet.ServletException {
        if (request.getHeader("HX-Request") != null) {
            response.setHeader("HX-Redirect", target);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        redirect.commence(request, response, exception);
    }
}
