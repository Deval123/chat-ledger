package dev.chatledger.board;

import dev.chatledger.matching.MatchingService;
import dev.chatledger.orders.IngestionResult;
import dev.chatledger.orders.OrderApprovalService;
import dev.chatledger.orders.OrderIngestionService;
import dev.chatledger.orders.TenantMoney;
import dev.chatledger.payments.PaymentIngestionResult;
import dev.chatledger.payments.PaymentIngestionService;
import dev.chatledger.security.RateDecision;
import dev.chatledger.security.RateLimits;
import dev.chatledger.security.TenantUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * Everything that changes something: the two paste forms and the seller's actions. Each handler has its own permission, is a POST (so the
 * CSRF token is required), counts against the per-user action limit, and ends in one of two answers from the same code: a redirect to the
 * board with a notice code (no script needed), or, when htmx asked, only the changed part of the page.
 *
 * <p>The services decide and refuse; this class only names the refusal in a fixed sentence ({@link Notice}). An id that is not the tenant's
 * is a 404, never a 403, so nothing says that it exists elsewhere.
 */
@Controller
public class ActionController {

    private final OrderIngestionService orders;
    private final PaymentIngestionService payments;
    private final MatchingService matching;
    private final OrderApprovalService approval;
    private final BoardRepository board;
    private final RateLimits limits;
    private final Shell shell;
    private final BooksCheck books;
    private final dev.chatledger.flags.FlagService flags;

    public ActionController(OrderIngestionService orders, PaymentIngestionService payments, MatchingService matching, OrderApprovalService approval,
                            BoardRepository board, RateLimits limits, Shell shell, BooksCheck books,
                            dev.chatledger.flags.FlagService flags) {
        this.flags = flags;
        this.books = books;
        this.orders = orders;
        this.payments = payments;
        this.matching = matching;
        this.approval = approval;
        this.board = board;
        this.limits = limits;
        this.shell = shell;
    }

    // ── Paste ──────────────────────────────────────────────────────────────────

    @PostMapping("/app/orders")
    @PreAuthorize("hasAuthority('RECORD_ORDER')")
    public String pasteOrder(@AuthenticationPrincipal TenantUserDetails user, @RequestParam("mid") UUID mid, @RequestParam("text") String text,
                             HttpServletRequest request, HttpServletResponse response, Model model) {
        throttle(response, limits.action(user.userId()));
        IngestionResult result = orders.ingest(user.tenantId(), user.userId(), "web:" + mid, text);
        Notice notice = switch (result.outcome()) {
            case CREATED -> Notice.ORDER_RECORDED;
            case CREATED_NEEDS_REVIEW -> Notice.ORDER_NEEDS_REVIEW;
            case CREATED_UNPARSED -> Notice.ORDER_UNPARSED;
            case DUPLICATE -> Notice.ORDER_REPEAT;
            case DUPLICATE_DIFFERENT_TEXT -> Notice.ORDER_REPEAT_DIFFERENT;
            case REJECTED_INPUT -> Notice.ORDER_INPUT_REJECTED;
            case RATE_LIMITED -> throw tooMany(response, result.retryAfterSeconds());
        };
        return answer(request, model, user, notice, result.orderId(), "fragments/board :: pasteDone");
    }

    @PostMapping("/app/payments")
    @PreAuthorize("hasAuthority('RECORD_PAYMENT')")
    public String pastePayment(@AuthenticationPrincipal TenantUserDetails user, @RequestParam("mid") UUID mid, @RequestParam("text") String text,
                               HttpServletRequest request, HttpServletResponse response, Model model) {
        throttle(response, limits.action(user.userId()));
        PaymentIngestionResult result = payments.ingest(user.tenantId(), user.userId(), "web:" + mid, text);
        Notice notice = switch (result.outcome()) {
            case CREATED -> Notice.PAYMENT_RECORDED;
            case CREATED_UNVERIFIED -> Notice.PAYMENT_UNVERIFIED;
            case CREATED_DUPLICATE_TX -> Notice.PAYMENT_DUPLICATE;
            case CREATED_UNPARSED -> Notice.PAYMENT_UNPARSED;
            case REPLAY -> Notice.PAYMENT_REPEAT;
            case REPLAY_DIFFERENT_TEXT -> Notice.PAYMENT_REPEAT_DIFFERENT;
            case REJECTED_INPUT -> Notice.PAYMENT_INPUT_REJECTED;
            case RATE_LIMITED -> throw tooMany(response, result.retryAfterSeconds());
        };
        return answer(request, model, user, notice, result.paymentId(), "fragments/board :: pasteDone");
    }

    // ── Decisions on suggestions and matches ───────────────────────────────────

    @PostMapping("/app/matches/{id}/confirm")
    @PreAuthorize("hasAuthority('CONFIRM_OR_OVERRIDE_MATCH')")
    public String confirm(@AuthenticationPrincipal TenantUserDetails user, @PathVariable UUID id, HttpServletRequest request,
                          HttpServletResponse response, Model model) {
        throttle(response, limits.action(user.userId()));
        return answer(request, model, user, applied(matching.confirm(user.tenantId(), id, user.userId()), Notice.MATCH_CONFIRMED), null, "fragments/board :: done");
    }

    @PostMapping("/app/matches/{id}/reject")
    @PreAuthorize("hasAuthority('CONFIRM_OR_OVERRIDE_MATCH')")
    public String reject(@AuthenticationPrincipal TenantUserDetails user, @PathVariable UUID id, HttpServletRequest request,
                         HttpServletResponse response, Model model) {
        throttle(response, limits.action(user.userId()));
        return answer(request, model, user, decided(matching.reject(user.tenantId(), id, user.userId()), Notice.MATCH_REJECTED), null, "fragments/board :: done");
    }

    @PostMapping("/app/matches/manual")
    @PreAuthorize("hasAuthority('CONFIRM_OR_OVERRIDE_MATCH')")
    public String manual(@AuthenticationPrincipal TenantUserDetails user, @RequestParam("orderId") UUID orderId, @RequestParam("paymentId") UUID paymentId,
                         HttpServletRequest request, HttpServletResponse response, Model model) {
        throttle(response, limits.action(user.userId()));
        return answer(request, model, user, applied(matching.matchManually(user.tenantId(), orderId, paymentId, user.userId()), Notice.MATCH_MANUAL), null,
                "fragments/board :: done");
    }

    @PostMapping("/app/matches/{id}/unmatch")
    @PreAuthorize("hasAuthority('REVERSE_TRANSACTION')")
    public String unmatch(@AuthenticationPrincipal TenantUserDetails user, @PathVariable UUID id, HttpServletRequest request,
                          HttpServletResponse response, Model model) {
        throttle(response, limits.action(user.userId()));
        return answer(request, model, user, decided(matching.unmatch(user.tenantId(), id, user.userId()), Notice.MATCH_UNDONE), null, "fragments/board :: done");
    }

    @PostMapping("/app/payments/{id}/set-aside")
    @PreAuthorize("hasAuthority('CONFIRM_OR_OVERRIDE_MATCH')")
    public String setAside(@AuthenticationPrincipal TenantUserDetails user, @PathVariable UUID id, HttpServletRequest request,
                           HttpServletResponse response, Model model) {
        throttle(response, limits.action(user.userId()));
        return answer(request, model, user, decided(matching.markUnmatched(user.tenantId(), id, user.userId()), Notice.PAYMENT_SET_ASIDE), null,
                "fragments/board :: done");
    }

    @PostMapping("/app/payments/{id}/take-back")
    @PreAuthorize("hasAuthority('CONFIRM_OR_OVERRIDE_MATCH')")
    public String takeBack(@AuthenticationPrincipal TenantUserDetails user, @PathVariable UUID id, HttpServletRequest request,
                           HttpServletResponse response, Model model) {
        throttle(response, limits.action(user.userId()));
        return answer(request, model, user, decided(matching.reopen(user.tenantId(), id, user.userId()), Notice.PAYMENT_TAKEN_BACK), null,
                "fragments/board :: done");
    }

    @PostMapping("/app/rematch")
    @PreAuthorize("hasAuthority('CONFIRM_OR_OVERRIDE_MATCH')")
    public String rematch(@AuthenticationPrincipal TenantUserDetails user, HttpServletRequest request, HttpServletResponse response, Model model) {
        throttle(response, limits.action(user.userId()));
        RateDecision decision = limits.rematch(user.tenantId());
        if (!decision.allowed()) {
            throw tooMany(response, decision.retryAfterSeconds());
        }
        MatchingService.RematchResult result = matching.rematch(user.tenantId(), user.userId());
        Notice notice = result.refusal() == null ? Notice.REMATCHED : Notice.refusal(result.refusal());
        return answer(request, model, user, notice, null, "fragments/board :: done");
    }

    // ── A total for an order that needs review ─────────────────────────────────

    @PostMapping("/app/orders/{id}/approve")
    @PreAuthorize("hasAuthority('APPROVE_ORDER_TOTAL')")
    public String approve(@AuthenticationPrincipal TenantUserDetails user, @PathVariable UUID id, @RequestParam("total") String total,
                          @RequestParam(name = "confirm", required = false) String confirm,
                          HttpServletRequest request, HttpServletResponse response, Model model) {
        throttle(response, limits.action(user.userId()));
        TenantMoney money = board.tenant(user.tenantId()).map(t -> new TenantMoney(t.currency(), t.scale()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        OptionalLong minor = MoneyFormat.parse(total, money.scale());
        Notice notice;
        if (minor.isEmpty()) {
            notice = Notice.APPROVE_NOT_A_NUMBER;
        } else if (!"yes".equals(confirm)) {
            // The second look (D12): the amount as it will be posted, and nothing written yet. The order must be this tenant's and in review.
            BoardRepository.OrderDetail order = board.order(user.tenantId(), id, false)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (!"NEEDS_REVIEW".equals(order.status())) {
                notice = Notice.APPROVE_NOT_REVIEWABLE;
            } else if (minor.getAsLong() < 1 || minor.getAsLong() > dev.chatledger.extraction.OrderLimits.MAX_MINOR) {
                notice = Notice.APPROVE_TOTAL_INVALID;
            } else {
                shell.fill(model, user);
                model.addAttribute("order", order);
                model.addAttribute("totalMinor", minor.getAsLong());
                return "app/approve";
            }
        } else {
            notice = switch (approval.approve(user.tenantId(), id, user.userId(), minor.getAsLong())) {
                case APPROVED -> Notice.ORDER_APPROVED;
                case NOT_A_SELLER -> Notice.REFUSED_NOT_A_SELLER;
                case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                case NOT_REVIEWABLE -> Notice.APPROVE_NOT_REVIEWABLE;
                case TOTAL_OUT_OF_RANGE -> Notice.APPROVE_TOTAL_INVALID;
            };
        }
        return answer(request, model, user, notice, null, "fragments/board :: done");
    }

    // ── Flags ──────────────────────────────────────────────────────────────────

    /**
     * "I checked this": the seller resolves one flag. One click, no check page (task 7, D7): it moves no money and does not hand the pair back
     * to the engine. A plain post redirects to the page the flag belongs to (built from ids the database returned, never from the request).
     */
    @PostMapping("/app/flags/{id}/resolve")
    @PreAuthorize("hasAuthority('RESOLVE_FLAG')")
    public String resolveFlag(@AuthenticationPrincipal TenantUserDetails user, @PathVariable UUID id, HttpServletRequest request,
                              HttpServletResponse response, Model model) {
        throttle(response, limits.action(user.userId()));
        dev.chatledger.flags.FlagService.Result result = flags.resolve(user.tenantId(), id, user.userId());
        Notice notice = switch (result.outcome()) {
            case RESOLVED -> Notice.FLAG_RESOLVED;
            case ALREADY_RESOLVED -> Notice.FLAG_ALREADY_RESOLVED;
            case NOT_A_SELLER -> Notice.REFUSED_NOT_A_SELLER;
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
        if (!"true".equals(request.getHeader("HX-Request")) && result.page() != null) {
            return "redirect:" + result.page() + "?n=" + notice.name();
        }
        return answer(request, model, user, notice, null, "fragments/board :: done");
    }

    // ── The books ──────────────────────────────────────────────────────────────

    /** Runs the check now instead of showing the result of the last five minutes. */
    @PostMapping("/app/books/check")
    @PreAuthorize("hasAuthority('VIEW_LEDGER')")
    public String checkBooks(@AuthenticationPrincipal TenantUserDetails user, HttpServletResponse response) {
        throttle(response, limits.action(user.userId()));
        books.check(user.tenantId(), true);
        return "redirect:/app/books";
    }

    // ── Shared ─────────────────────────────────────────────────────────────────

    private static Notice applied(MatchingService.ApplyResult result, Notice success) {
        return switch (result.outcome()) {
            case APPLIED -> success;
            case DEGRADED_TO_SUGGESTION -> Notice.MATCH_NOT_APPLIED;
            case NOT_APPLICABLE -> refusal(result.reason());
        };
    }

    private static Notice decided(MatchingService.Decision decision, Notice success) {
        return decision.done() ? success : refusal(decision.reason());
    }

    private static Notice refusal(MatchingService.Reason reason) {
        if (reason == MatchingService.Reason.NOT_FOUND) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return Notice.refusal(reason);
    }

    /** One user cannot hammer the actions: over the limit is a 429 with the time to wait. */
    private void throttle(HttpServletResponse response, RateDecision decision) {
        if (!decision.allowed()) {
            throw tooMany(response, decision.retryAfterSeconds());
        }
    }

    private static ResponseStatusException tooMany(HttpServletResponse response, long retryAfterSeconds) {
        response.setHeader("Retry-After", Long.toString(Math.max(1, retryAfterSeconds)));
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** The redirect (no script) or, for htmx, the changed part of the page. The same decision either way. */
    private String answer(HttpServletRequest request, Model model, TenantUserDetails user, Notice notice, UUID subject, String fragment) {
        if (!"true".equals(request.getHeader("HX-Request"))) {
            return "redirect:/app?n=" + notice.name() + (subject == null ? "" : "&r=" + subject);
        }
        shell.fill(model, user);
        shell.counts(model, user.tenantId());
        model.addAttribute("notice", notice);
        if (subject != null) {
            model.addAttribute("noticeLink", (notice.name().startsWith("ORDER_") ? "/app/orders/" : "/app/payments/") + subject);
        }
        model.addAttribute("mid1", UUID.randomUUID());
        model.addAttribute("mid2", UUID.randomUUID());
        return fragment;
    }
}
