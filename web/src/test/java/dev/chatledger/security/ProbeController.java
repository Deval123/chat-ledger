package dev.chatledger.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Stand-in handlers for actions that do not exist yet (export, delete, user management, recording an
 * order), so permissions can be tested end to end now. Active only with the {@code probe} profile, which
 * only tests switch on; it is not in the application jar.
 */
@Controller
@Profile("probe")
class ProbeController {

    private final RateLimits limits;

    ProbeController(RateLimits limits) {
        this.limits = limits;
    }

    /** Stands in for message ingestion: the tenant limit, keyed by the principal's tenant. */
    @PostMapping("/app/probe/ingest")
    @PreAuthorize("hasAuthority('RECORD_ORDER')")
    org.springframework.http.ResponseEntity<String> ingest(@AuthenticationPrincipal TenantUserDetails user) {
        RateDecision decision = limits.ingestion(user.tenantId());
        if (!decision.allowed()) {
            return org.springframework.http.ResponseEntity.status(429)
                    .header("Retry-After", Long.toString(decision.retryAfterSeconds())).body("Too many requests. Try again shortly.");
        }
        return org.springframework.http.ResponseEntity.ok("ingested");
    }

    /** Stands in for a JSON endpoint: echoes the length of the body it was given. */
    @PostMapping(value = "/app/probe/json", consumes = "application/json")
    @ResponseBody
    @PreAuthorize("hasAuthority('RECORD_ORDER')")
    String json(@org.springframework.web.bind.annotation.RequestBody String body) {
        return "length=" + body.length();
    }

    /** Stands in for a form that takes free text. */
    @PostMapping("/app/probe/note")
    @ResponseBody
    @PreAuthorize("hasAuthority('RECORD_ORDER')")
    String note(@org.springframework.web.bind.annotation.RequestParam String text) {
        return "note=" + text;
    }

    @GetMapping("/app/probe/whoami")
    @ResponseBody
    @PreAuthorize("hasAuthority('VIEW_BOARD')")
    String whoami(@AuthenticationPrincipal TenantUserDetails user) {
        return user.tenantId() + " " + user.userId() + " " + user.role();
    }

    @GetMapping("/app/probe/session")
    @ResponseBody
    @PreAuthorize("hasAuthority('VIEW_BOARD')")
    String session(HttpServletRequest request) {
        return String.valueOf(request.getSession().getMaxInactiveInterval());
    }

    @PostMapping("/app/probe/record")
    @ResponseBody
    @PreAuthorize("hasAuthority('RECORD_ORDER')")
    String record() {
        return "recorded";
    }

    @GetMapping("/admin/probe/export")
    @ResponseBody
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    String export() {
        return "exported";
    }

    @PostMapping("/admin/probe/delete")
    @ResponseBody
    @PreAuthorize("hasAuthority('DELETE_TENANT_DATA')")
    String delete() {
        return "deleted";
    }

    @GetMapping("/admin/probe/users")
    @ResponseBody
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    String users() {
        return "users";
    }

    /** Under /admin, so the URL rule applies; the method rule is what a later refactor could lose. */
    @GetMapping("/admin/probe/ledger")
    @ResponseBody
    @PreAuthorize("hasAuthority('VIEW_LEDGER')")
    String ledger() {
        return "ledger";
    }
}
