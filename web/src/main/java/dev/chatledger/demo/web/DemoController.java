package dev.chatledger.demo.web;

import dev.chatledger.board.BoardRepository;
import dev.chatledger.board.Notice;
import dev.chatledger.board.Shell;
import dev.chatledger.demo.Scenario;
import dev.chatledger.demo.Script;
import dev.chatledger.security.RateDecision;
import dev.chatledger.security.RateLimits;
import dev.chatledger.security.TenantUserDetails;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The simulated chat and confirmation feed (requirement 7.1) and the seven one-click scenarios (7.2), for a demo workspace only. The
 * tenant comes from the principal and must be a demo workspace in the database ({@code tenant.kind}); any other gets 404, as an unknown
 * page. The routes take a scenario and a step from fixed lists and no text at all. Plain forms, post then redirect: the order step returns
 * to this page, a payment step to the board, where its outcome is.
 */
@Controller
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoController {

    static final String SCRIPTS = "chatledger.demo.scripts";

    /** One scenario card: its scenario, the script shown (the one waiting for its payment, or a sample), and whether its order was sent. */
    public record Card(Scenario scenario, Script script, boolean waiting) {
    }

    private final Shell shell;
    private final BoardRepository board;
    private final DemoScenarios scenarios;
    private final RateLimits limits;

    public DemoController(Shell shell, BoardRepository board, DemoScenarios scenarios, RateLimits limits) {
        this.shell = shell;
        this.board = board;
        this.scenarios = scenarios;
        this.limits = limits;
    }

    @GetMapping("/app/demo")
    @PreAuthorize("hasAuthority('VIEW_BOARD')")
    public String page(@AuthenticationPrincipal TenantUserDetails user, @RequestParam(name = "n", required = false) String n,
                       HttpSession session, Model model) {
        BoardRepository.TenantInfo tenant = shell.fill(model, user);
        if (!tenant.demo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Map<Scenario, Script> store = store(session);
        List<Card> cards = new ArrayList<>();
        for (Scenario s : Scenario.values()) {
            Script waiting = store.get(s);
            cards.add(new Card(s, waiting != null ? waiting : s.script(scenarios.messages()), waiting != null));
        }
        model.addAttribute("cards", cards);
        Notice.parse(n).ifPresent(notice -> model.addAttribute("notice", notice));
        return "app/demo";
    }

    @PostMapping("/app/demo/scenarios/{scenario}/{step}")
    @PreAuthorize("hasAuthority('RECORD_ORDER') and hasAuthority('RECORD_PAYMENT')")
    public String run(@AuthenticationPrincipal TenantUserDetails user, @PathVariable String scenario, @PathVariable String step,
                      HttpSession session, HttpServletResponse response) {
        if (!board.tenant(user.tenantId()).map(BoardRepository.TenantInfo::demo).orElse(false)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Scenario which = Scenario.byPath(scenario).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        DemoScenarios.Step what = DemoScenarios.Step.byPath(step).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        throttle(response, limits.action(user.userId()));
        throttle(response, limits.demoScenario(user.tenantId()));
        Map<Scenario, Script> store = store(session);
        DemoScenarios.Outcome outcome = scenarios.run(user.tenantId(), user.userId(), which, what, store);
        session.setAttribute(SCRIPTS, store);
        if (outcome.rateLimited()) {
            throttle(response, RateDecision.deny(outcome.retryAfterSeconds()));
        }
        return outcome.toBoard()
                ? "redirect:/app?n=" + outcome.notice().name()
                : "redirect:/app/demo?n=" + outcome.notice().name() + "#s-" + which.path();
    }

    @SuppressWarnings("unchecked")
    private static Map<Scenario, Script> store(HttpSession session) {
        Object stored = session.getAttribute(SCRIPTS);
        return stored instanceof EnumMap<?, ?> map ? (Map<Scenario, Script>) map : new EnumMap<>(Scenario.class);
    }

    private static void throttle(HttpServletResponse response, RateDecision decision) {
        if (!decision.allowed()) {
            response.setHeader("Retry-After", Long.toString(Math.max(1, decision.retryAfterSeconds())));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
    }
}
