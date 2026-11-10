package dev.chatledger.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;

/**
 * The sign-in page and the sign-in itself.
 *
 * <p>Every failure, whatever its cause, redirects to the same URL, so the response does not say
 * whether the workspace, the email or the password was wrong. On success the session id is replaced
 * (session fixation) and the CSRF token is renewed before the new session is used.
 *
 * <p>Nothing here logs a request value. The security log gets only a keyed hash of the submitted
 * workspace and email.
 */
@Controller
public class LoginController {

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;
    private final Clock clock;
    private final SecurityProperties properties;
    private final SecurityEvents events;
    private final KeyedHasher hasher;
    private final AuditLogRepository audit;
    private final RateLimits limits;
    private final boolean demoEnabled;

    public LoginController(AuthenticationManager authenticationManager, SessionAuthenticationStrategy sessionStrategy,
                           SecurityContextRepository contextRepository, Clock clock, SecurityProperties properties,
                           SecurityEvents events, KeyedHasher hasher, AuditLogRepository audit,
                           RateLimits limits, @org.springframework.beans.factory.annotation.Value("${app.demo.enabled:false}") boolean demoEnabled) {
        this.authenticationManager = authenticationManager;
        this.sessionStrategy = sessionStrategy;
        this.contextRepository = contextRepository;
        this.clock = clock;
        this.properties = properties;
        this.events = events;
        this.hasher = hasher;
        this.audit = audit;
        this.limits = limits;
        this.demoEnabled = demoEnabled;
    }

    @GetMapping("/login")
    public String loginPage(HttpServletRequest request, org.springframework.ui.Model model) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current != null && current.isAuthenticated() && !(current instanceof AnonymousAuthenticationToken)) {
            return "redirect:/app";
        }
        // The page carries a CSRF token, which lives in a session. Keep that session short.
        request.getSession(true).setMaxInactiveInterval((int) properties.session().anonymousTimeout().toSeconds());
        model.addAttribute("demoEnabled", demoEnabled);
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam(defaultValue = "") String workspace,
                        @RequestParam(defaultValue = "") String email,
                        @RequestParam(defaultValue = "") String password,
                        HttpServletRequest request, HttpServletResponse response) {
        // One key per submitted account, real or invented, so the limit looks the same for both.
        String accountKey = hasher.shortHash(workspace.strip().toLowerCase() + "|" + email.strip().toLowerCase());
        RateDecision allowed = limits.loginForAccount(accountKey);
        if (!allowed.allowed()) {
            throw new TooManyAttemptsException(allowed.retryAfterSeconds(), request.getRemoteAddr());
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(WorkspaceAuthenticationToken.attempt(workspace, email, password));
        } catch (BadCredentialsException failure) {
            events.loginFailed(accountKey, request.getRemoteAddr());
            return "redirect:/login?error";
        }

        var user = (TenantUserDetails) authentication.getPrincipal();
        // Written first: if the audit trail cannot be written, no session is created.
        audit.append(user.tenantId(), user.userId(), "LOGIN_SUCCESS", "session");

        sessionStrategy.onAuthentication(authentication, request, response);   // new session id, new CSRF token

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        HttpSession session = request.getSession();
        session.setMaxInactiveInterval((int) properties.session().idleTimeout().toSeconds());
        var now = clock.instant();
        session.setAttribute(SessionAttributes.AUTHENTICATED_AT, now);
        session.setAttribute(SessionAttributes.STATUS_CHECKED_AT, now);

        limits.loginSucceeded(accountKey);
        events.loginSucceeded(user.userId(), user.tenantId());
        return "redirect:/app";
    }

    /** Too many attempts for one account: the same refusal for accounts that exist and ones that do not. */
    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<String> tooMany(TooManyAttemptsException e) {
        events.rateLimited("login-account", e.clientAddress());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(e.retryAfterSeconds()))
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .body("Too many requests. Try again shortly.");
    }

    /** Too many password checks at once: refuse, the same way for everyone. */
    @ExceptionHandler(LoginCapacityException.class)
    public ResponseEntity<String> busy(HttpServletRequest request) {
        events.loginRefusedBusy(request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "2")
                .body("Too many sign-in attempts. Try again shortly.");
    }
}
