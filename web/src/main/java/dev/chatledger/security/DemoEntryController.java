package dev.chatledger.security;

import dev.chatledger.demo.web.DemoLeases;
import dev.chatledger.demo.web.DemoProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The public door of the demo (task 9, D2): one page with one button, and the button. It exists only on the demo deployment
 * ({@code app.demo.enabled=true}, and the database in {@code DEMO} mode or the application does not start).
 *
 * <p>The request carries no tenant, workspace, user or text: the database's {@code demo_claim} hands out one ready demo workspace, and the
 * principal is built from that workspace's seller row, read back exactly as sign-in reads a user. Nobody knows that seller's password, and
 * password sign-in refuses demo workspaces, so the two doors never lead to each other's workspaces.
 *
 * <p>A visitor who is already in a workspace is sent back to it and asks for no new one (D22). The page is limited per address before
 * CSRF ({@link RateLimitFilter}); the claim has its own per-address and global limits, spent only when a workspace is asked for.
 */
@Controller
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoEntryController {

    private final DemoLeases leases;
    private final DemoProperties demo;
    private final AppUserRepository users;
    private final AuditLogRepository audit;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;
    private final SecurityProperties properties;
    private final SecurityEvents events;
    private final RateLimits limits;
    private final Clock clock;

    public DemoEntryController(DemoLeases leases, DemoProperties demo, AppUserRepository users, AuditLogRepository audit, SessionAuthenticationStrategy sessionStrategy,
                               SecurityContextRepository contextRepository, SecurityProperties properties, SecurityEvents events,
                               RateLimits limits, Clock clock) {
        this.leases = leases;
        this.demo = demo;
        this.users = users;
        this.audit = audit;
        this.sessionStrategy = sessionStrategy;
        this.contextRepository = contextRepository;
        this.properties = properties;
        this.events = events;
        this.limits = limits;
        this.clock = clock;
    }

    @GetMapping("/demo")
    public String page(HttpServletRequest request, Model model) {
        if (signedInToAWorkspace(request)) {
            return "redirect:/app";
        }
        // The page carries a CSRF token, which lives in a session. Keep that session short.
        request.getSession(true).setMaxInactiveInterval((int) properties.session().anonymousTimeout().toSeconds());
        model.addAttribute("busy", request.getParameter("busy") != null);
        return "demo";
    }

    @PostMapping("/demo")
    public String enter(HttpServletRequest request, HttpServletResponse response) {
        if (signedInToAWorkspace(request)) {
            return "redirect:/app";   // D22: back to the workspace already leased, no new lease
        }
        String address = ClientAddress.of(request, properties.trustedProxyHops());
        RateDecision allowed = limits.demoClaim(address);
        if (!allowed.allowed()) {
            events.rateLimited("demo-claim", address);
            response.setHeader("Retry-After", Long.toString(Math.max(1, allowed.retryAfterSeconds())));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        Optional<DemoLeases.Lease> lease = leases.claim(demo.lease());
        if (lease.isEmpty()) {
            events.demoRefused("none-ready");
            return "redirect:/demo?busy";
        }
        // Read back as sign-in does: an enabled SELLER of an enabled workspace, or nothing. (That it is a demo workspace is the database's
        // to say: demo_claim reads only kind = 'DEMO', and a demo_tenant row can name no other kind, fk_demo_tenant_is_demo.)
        Optional<AppUser> user = users.findById(lease.get().tenantId(), lease.get().sellerId());
        if (user.isEmpty() || !user.get().enabled() || user.get().role() != Role.SELLER) {
            events.demoRefused("not-a-demo-seller");
            return "redirect:/demo?busy";
        }
        AppUser found = user.get();
        TenantUserDetails principal = new TenantUserDetails(found.id(), found.tenantId(), found.role());
        // Written first: if the audit trail cannot be written, no session is created.
        audit.append(found.tenantId(), found.id(), "DEMO_ENTERED", "session");

        Authentication authentication = WorkspaceAuthenticationToken.signedIn(principal);
        sessionStrategy.onAuthentication(authentication, request, response);   // new session id, new CSRF token
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        HttpSession session = request.getSession();
        session.setMaxInactiveInterval((int) properties.session().idleTimeout().toSeconds());
        Instant now = clock.instant();
        session.setAttribute(SessionAttributes.AUTHENTICATED_AT, now);
        session.setAttribute(SessionAttributes.STATUS_CHECKED_AT, now);
        session.setAttribute(SessionAttributes.ENDS_AT, now.plus(demo.lease()));
        events.demoEntered(found.id(), found.tenantId());
        return "redirect:/app";
    }

    /**
     * True if this session already holds a user whose row is still enabled (re-read now, not waiting for the status filter). A session
     * whose workspace was retired is ended here, so its visitor can start a new demo.
     */
    private boolean signedInToAWorkspace(HttpServletRequest request) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current == null || !(current.getPrincipal() instanceof TenantUserDetails principal)) {
            return false;
        }
        Optional<AppUser> row = users.findById(principal.tenantId(), principal.userId());
        if (row.isPresent() && row.get().enabled()) {
            return true;
        }
        events.sessionEnded("demo-workspace-retired", principal.userId(), principal.tenantId());
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return false;
    }
}
