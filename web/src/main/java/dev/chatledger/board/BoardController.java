package dev.chatledger.board;

import dev.chatledger.board.BoardRepository.Column;
import dev.chatledger.board.BoardRepository.Cursor;
import dev.chatledger.board.BoardRepository.OrderDetail;
import dev.chatledger.board.BoardRepository.PaymentDetail;
import dev.chatledger.matching.MatchRepository;
import dev.chatledger.security.Role;
import dev.chatledger.security.TenantUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The read pages: the board, one column, the detail of an order or a payment, the check pages before a decision, the daily summary and
 * the books check. Nothing here changes data. The tenant and the user come from the principal; an id that is not this tenant's is a 404.
 *
 * <p>What a helper does not get is decided here and nowhere else: the raw text of a message, the money totals of the summary and the books
 * are never even read for a helper.
 */
@Controller
public class BoardController {

    static final int PAGE_SIZE = 20;
    static final int MAX_PAGES = 5;
    private static final List<Column> BOARD_COLUMNS = List.of(Column.AWAITING, Column.REVIEW, Column.MATCHED, Column.FLAGGED, Column.UNPARSED);
    private static final LocalDate EARLIEST_DAY = LocalDate.of(2000, 1, 1);

    private final BoardRepository board;
    private final MatchRepository matches;
    private final Explainer explainer;
    private final BooksCheck books;
    private final PhoneMask phone;
    private final Display display;
    private final Shell shell;
    private final Clock clock;

    public BoardController(BoardRepository board, MatchRepository matches, Explainer explainer, BooksCheck books, PhoneMask phone,
                           Display display, Shell shell, Clock clock) {
        this.board = board;
        this.matches = matches;
        this.explainer = explainer;
        this.books = books;
        this.phone = phone;
        this.display = display;
        this.shell = shell;
        this.clock = clock;
    }

    // ── The board ──────────────────────────────────────────────────────────────

    @GetMapping("/app")
    @PreAuthorize("hasAuthority('VIEW_BOARD')")
    public String board(@AuthenticationPrincipal TenantUserDetails user, @RequestParam(name = "n", required = false) String n,
                        @RequestParam(name = "r", required = false) UUID r, Model model) {
        BoardRepository.TenantInfo tenant = shell.fill(model, user);
        shell.counts(model, user.tenantId());
        Map<Column, BoardRepository.Page> pages = new LinkedHashMap<>();
        for (Column column : BOARD_COLUMNS) {
            pages.put(column, board.column(user.tenantId(), column, null, PAGE_SIZE));
        }
        model.addAttribute("pages", pages);
        model.addAttribute("mid1", UUID.randomUUID());
        model.addAttribute("mid2", UUID.randomUUID());
        notice(model, n, r);
        summaryStrip(model, user, tenant, LocalDate.now(clock.withZone(tenant.zone())));
        if (user.role() == Role.SELLER) {
            model.addAttribute("booksState", books.lastKnown(user.tenantId()).map(result -> result.balanced() ? "ok" : "bad").orElse("unknown"));
        }
        return "app/board";
    }

    @GetMapping("/app/columns/{column}")
    @PreAuthorize("hasAuthority('VIEW_BOARD')")
    public String column(@AuthenticationPrincipal TenantUserDetails user, @PathVariable Column column,
                         @RequestParam(name = "after", required = false) String after,
                         @RequestParam(name = "page", defaultValue = "1") int page, Model model) {
        if (page < 1 || page > MAX_PAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        Cursor cursor = null;
        if (after != null) {
            cursor = Cursor.parse(after).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));
        }
        shell.fill(model, user);
        shell.counts(model, user.tenantId());
        BoardRepository.Page found = board.column(user.tenantId(), column, cursor, PAGE_SIZE);
        model.addAttribute("column", column);
        model.addAttribute("pageNumber", page);
        model.addAttribute("more", found.next() != null && page < MAX_PAGES);
        model.addAttribute("next", found.next() == null ? "" : found.next().format());
        model.addAttribute("cards", found.cards());
        return "app/column";
    }

    private void notice(Model model, String code, UUID subject) {
        Notice.parse(code).ifPresent(notice -> {
            model.addAttribute("notice", notice);
            if (subject != null) {
                String name = notice.name();
                if (name.startsWith("ORDER_")) {
                    model.addAttribute("noticeLink", "/app/orders/" + subject);
                } else if (name.startsWith("PAYMENT_")) {
                    model.addAttribute("noticeLink", "/app/payments/" + subject);
                }
            }
        });
    }

    private void summaryStrip(Model model, TenantUserDetails user, BoardRepository.TenantInfo tenant, LocalDate day) {
        Instant from = day.atStartOfDay(tenant.zone()).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(tenant.zone()).toInstant();
        model.addAttribute("day", day);
        model.addAttribute("summary", board.summaryCounts(user.tenantId(), from, to));
        if (user.role().allows(dev.chatledger.security.Permission.VIEW_LEDGER)) {
            model.addAttribute("amounts", board.summaryAmounts(user.tenantId(), from, to));
        }
    }

    // ── Details ────────────────────────────────────────────────────────────────

    @GetMapping("/app/orders/{id}")
    @PreAuthorize("hasAuthority('VIEW_BOARD')")
    public String order(@AuthenticationPrincipal TenantUserDetails user, @PathVariable UUID id,
                        @RequestParam(name = "n", required = false) String n, Model model) {
        shell.fill(model, user);
        boolean seller = user.role() == Role.SELLER;
        OrderDetail order = board.order(user.tenantId(), id, seller).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        long balance = order.totalMinor() == null ? 0 : Math.max(0, order.totalMinor() - order.appliedMinor());
        List<Explainer.Shown> shown = order.matches().stream().map(m -> explainer.explain(user.tenantId(), user.userId(), m, order.id(), m.otherId(),
                m.otherAmountMinor() == null ? 0 : m.otherAmountMinor(), balance)).toList();
        model.addAttribute("order", order);
        model.addAttribute("shown", shown);
        model.addAttribute("raw", order.rawText() == null ? null : display.block(phone.maskText(order.rawText()), 1000));
        model.addAttribute("balance", balance);
        notice(model, n, null);
        return "app/order";
    }

    @GetMapping("/app/payments/{id}")
    @PreAuthorize("hasAuthority('VIEW_BOARD')")
    public String payment(@AuthenticationPrincipal TenantUserDetails user, @PathVariable UUID id,
                          @RequestParam(name = "n", required = false) String n, Model model) {
        shell.fill(model, user);
        boolean seller = user.role() == Role.SELLER;
        PaymentDetail payment = board.payment(user.tenantId(), id, seller).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<Explainer.Shown> shown = payment.matches().stream().map(m -> explainer.explain(user.tenantId(), user.userId(), m, m.otherId(), payment.id(),
                payment.amountMinor(), m.otherAmountMinor() == null ? 0 : Math.max(0, m.otherAmountMinor() - m.otherAppliedMinor()))).toList();
        model.addAttribute("payment", payment);
        model.addAttribute("shown", shown);
        model.addAttribute("raw", payment.rawText() == null ? null : display.block(phone.maskText(payment.rawText()), 1000));
        model.addAttribute("free", "RECEIVED".equals(payment.status()) || "UNVERIFIED".equals(payment.status()));
        notice(model, n, null);
        return "app/payment";
    }

    // ── Check pages ────────────────────────────────────────────────────────────

    @GetMapping("/app/orders/{id}/review")
    @PreAuthorize("hasAuthority('APPROVE_ORDER_TOTAL')")
    public String review(@AuthenticationPrincipal TenantUserDetails user, @PathVariable UUID id, Model model) {
        shell.fill(model, user);
        OrderDetail order = board.order(user.tenantId(), id, true).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"NEEDS_REVIEW".equals(order.status())) {
            return "redirect:/app/orders/" + id + "?n=" + Notice.APPROVE_NOT_REVIEWABLE.name();
        }
        model.addAttribute("order", order);
        model.addAttribute("raw", order.rawText() == null ? null : display.block(phone.maskText(order.rawText()), 1000));
        return "app/review";
    }

    @GetMapping("/app/payments/{id}/match")
    @PreAuthorize("hasAuthority('CONFIRM_OR_OVERRIDE_MATCH')")
    public String picker(@AuthenticationPrincipal TenantUserDetails user, @PathVariable UUID id, Model model) {
        shell.fill(model, user);
        PaymentDetail payment = board.payment(user.tenantId(), id, false).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!("RECEIVED".equals(payment.status()) || "UNVERIFIED".equals(payment.status()))) {
            return "redirect:/app/payments/" + id + "?n=" + Notice.REFUSED_PAYMENT_NOT_FREE.name();
        }
        model.addAttribute("payment", payment);
        model.addAttribute("orders", matches.openOrders(user.tenantId(), payment.currency(), 50));
        return "app/picker";
    }

    @GetMapping("/app/matches/{id}/undo")
    @PreAuthorize("hasAuthority('REVERSE_TRANSACTION')")
    public String undo(@AuthenticationPrincipal TenantUserDetails user, @PathVariable UUID id, Model model) {
        shell.fill(model, user);
        MatchRepository.Stored match = matches.find(user.tenantId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!("AUTO".equals(match.state()) || "CONFIRMED".equals(match.state()))) {
            return "redirect:/app/orders/" + match.orderId() + "?n=" + Notice.REFUSED_NOT_ACTIVE.name();
        }
        model.addAttribute("match", match);
        model.addAttribute("order", board.order(user.tenantId(), match.orderId(), false).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        model.addAttribute("payment", board.payment(user.tenantId(), match.paymentId(), false).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "app/undo";
    }

    // ── Summary and books ──────────────────────────────────────────────────────

    @GetMapping("/app/summary")
    @PreAuthorize("hasAuthority('VIEW_BOARD')")
    public String summary(@AuthenticationPrincipal TenantUserDetails user, @RequestParam(name = "date", required = false) String date, Model model) {
        BoardRepository.TenantInfo tenant = shell.fill(model, user);
        LocalDate today = LocalDate.now(clock.withZone(tenant.zone()));
        LocalDate day = date == null ? today : parseDay(date, today);
        summaryStrip(model, user, tenant, day);
        model.addAttribute("previous", day.minusDays(1).isBefore(EARLIEST_DAY) ? null : day.minusDays(1));
        model.addAttribute("following", day.isBefore(today) ? day.plusDays(1) : null);
        return "app/summary";
    }

    /** An ISO date between 2000-01-01 and today. A longer form the parser accepts (a signed year above 9999) is after today. */
    static LocalDate parseDay(String text, LocalDate today) {
        try {
            LocalDate day = LocalDate.parse(text);
            if (day.isBefore(EARLIEST_DAY) || day.isAfter(today)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            return day;
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    @GetMapping("/app/books")
    @PreAuthorize("hasAuthority('VIEW_LEDGER')")
    public String books(@AuthenticationPrincipal TenantUserDetails user, Model model) {
        shell.fill(model, user);
        model.addAttribute("result", books.check(user.tenantId(), false));
        return "app/books";
    }
}
