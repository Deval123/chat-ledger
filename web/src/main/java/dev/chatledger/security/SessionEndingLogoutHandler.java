package dev.chatledger.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

import java.time.Duration;

/**
 * Ends the session on sign-out and tells the browser to drop the cookie.
 *
 * <p>Two reasons it replaces the framework's handlers. The framework logs the session id when it
 * invalidates a session (at DEBUG), and a session id is a credential that must not reach a log. And
 * its cookie deletion omits {@code Secure}, which a browser requires before it will overwrite a
 * {@code __Host-} cookie, so the deletion would be ignored. This one sends the same attributes the
 * cookie was issued with.
 */
final class SessionEndingLogoutHandler implements LogoutHandler {

    private final String cookieName;
    private final boolean secure;

    SessionEndingLogoutHandler(String cookieName, boolean secure) {
        this.cookieName = cookieName;
        this.secure = secure;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        ResponseCookie expired = ResponseCookie.from(cookieName, "")
                .path("/").maxAge(Duration.ZERO).httpOnly(true).secure(secure).sameSite("Strict").build();
        response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
    }
}
