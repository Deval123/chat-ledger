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
    private final dev.chatledger.orders.OrderRepository orders;
    private final dev.chatledger.orders.CustomerRepository customers;

    ProbeController(RateLimits limits, dev.chatledger.orders.OrderRepository orders,
                    dev.chatledger.orders.CustomerRepository customers) {
        this.limits = limits;
        this.orders = orders;
        this.customers = customers;
    }

    /**
     * Stands in for the board (task 8): shows a stored order the way a page will, with every value from the message
     * (reference, name, note, item labels, raw text) going through {@code th:text}. The tenant is the principal's.
     */
    @GetMapping("/app/probe/order/{externalId}")
    @PreAuthorize("hasAuthority('VIEW_BOARD')")
    String order(@org.springframework.web.bind.annotation.PathVariable String externalId,
                 @AuthenticationPrincipal TenantUserDetails user, org.springframework.ui.Model model) {
        var order = orders.findByExternalId(user.tenantId(), externalId).orElseThrow(
                () -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        model.addAttribute("status", order.status());
        model.addAttribute("ref", order.orderRef());
        model.addAttribute("note", order.deliveryNote());
        model.addAttribute("raw", order.rawText());
        model.addAttribute("name", order.customerId() == null ? null
                : customers.find(user.tenantId(), order.customerId()).map(c -> c.displayName()).orElse(null));
        model.addAttribute("labels", orders.lines(user.tenantId(), order.id()).stream().map(l -> l.label()).toList());
        return "probe/order";
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
