package dev.chatledger.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import java.io.IOException;

/**
 * What an unauthenticated request gets. A normal page request is redirected to the login page. An htmx
 * request (it sends {@code HX-Request}) gets 401 and an {@code HX-Redirect} header instead, so the login
 * page is not swapped into the middle of another page.
 */
public class LoginEntryPoint implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint redirect = new LoginUrlAuthenticationEntryPoint("/login");

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, jakarta.servlet.ServletException {
        if (request.getHeader("HX-Request") != null) {
            response.setHeader("HX-Redirect", "/login");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        redirect.commence(request, response, exception);
    }
}
