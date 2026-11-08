package dev.chatledger.board;

import dev.chatledger.board.ExportRepository.LedgerRow;
import dev.chatledger.board.ExportRepository.OrderRow;
import dev.chatledger.board.ExportRepository.PaymentRow;
import dev.chatledger.security.AuditLogRepository;
import dev.chatledger.security.RateDecision;
import dev.chatledger.security.RateLimits;
import dev.chatledger.security.TenantUserDetails;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The three CSV downloads: orders, payments, the ledger. Seller only (the ledger needs the ledger permission too). A range of at most 366 days
 * and at most 100,000 rows; a larger one is refused, never cut. Text cells are neutralised by {@link CsvWriter}; phone numbers are masked.
 * Each download writes an audit row (who, which file, how many rows) and counts against the per-tenant export limit.
 */
@Controller
public class ExportController {

    /** The most rows one file may have; a setting may only lower it. */
    static final int MAX_ROWS_LIMIT = 100_000;
    static final int MAX_DAYS = 366;
    static final int DEFAULT_DAYS = 31;

    private final ExportRepository exports;
    private final BoardRepository board;
    private final AuditLogRepository audit;
    private final RateLimits limits;
    private final Shell shell;
    private final Clock clock;
    private final int maxRows;

    public ExportController(ExportRepository exports, BoardRepository board, AuditLogRepository audit, RateLimits limits, Shell shell, Clock clock,
                            @org.springframework.beans.factory.annotation.Value("${app.export.max-rows:100000}") int maxRows) {
        if (maxRows < 1 || maxRows > MAX_ROWS_LIMIT) {
            throw new IllegalArgumentException("app.export.max-rows must be between 1 and " + MAX_ROWS_LIMIT);
        }
        this.maxRows = maxRows;
        this.exports = exports;
        this.board = board;
        this.audit = audit;
        this.limits = limits;
        this.shell = shell;
        this.clock = clock;
    }

    @GetMapping("/app/export")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public String page(@AuthenticationPrincipal TenantUserDetails user, @RequestParam(name = "n", required = false) String n, Model model) {
        BoardRepository.TenantInfo tenant = shell.fill(model, user);
        LocalDate today = LocalDate.now(clock.withZone(tenant.zone()));
        model.addAttribute("files", List.of("orders", "payments", "ledger"));
        model.addAttribute("from", today.minusDays(DEFAULT_DAYS - 1));
        model.addAttribute("to", today);
        Notice.parse(n).ifPresent(notice -> model.addAttribute("notice", notice));
        return "app/export";
    }

    @GetMapping("/app/export/orders.csv")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public void orders(@AuthenticationPrincipal TenantUserDetails user, @RequestParam(name = "from", required = false) String from,
                         @RequestParam(name = "to", required = false) String to, HttpServletResponse response) throws IOException {
        Range range = range(user, from, to);
        if (!begin(user, response, "orders", range, exports.countOrders(user.tenantId(), range.from, range.to))) {
            return;
        }
        BoardRepository.TenantInfo tenant = tenantOf(user);
        Writer out = response.getWriter();
        out.write('\uFEFF');
        out.write(CsvWriter.line(List.of(CsvWriter.text("id"), CsvWriter.text("recorded_at_utc"), CsvWriter.text("status"), CsvWriter.text("order_code"),
                CsvWriter.text("customer"), CsvWriter.text("phone"), CsvWriter.text("total"), CsvWriter.text("applied"), CsvWriter.text("balance"),
                CsvWriter.text("currency"), CsvWriter.text("delivery_note"))));
        exports.orders(user.tenantId(), range.from, range.to, maxRows, (OrderRow r) -> {
            List<String> cells = new ArrayList<>();
            cells.add(CsvWriter.text(r.id().toString()));
            cells.add(CsvWriter.text(r.at().toString()));
            cells.add(CsvWriter.text(r.status()));
            cells.add(CsvWriter.text(r.ref()));
            cells.add(CsvWriter.text(r.who()));
            cells.add(CsvWriter.text(maskedPhone(r.last4())));
            cells.add(r.totalMinor() == null ? "" : CsvWriter.number(r.totalMinor(), tenant.scale()));
            cells.add(CsvWriter.number(r.appliedMinor(), tenant.scale()));
            cells.add(r.totalMinor() == null ? "" : CsvWriter.number(r.totalMinor() - r.appliedMinor(), tenant.scale()));
            cells.add(CsvWriter.text(r.currency()));
            cells.add(CsvWriter.text(r.note()));
            write(out, cells);
        });
        out.flush();
    }

    @GetMapping("/app/export/payments.csv")
    @PreAuthorize("hasAuthority('EXPORT_DATA')")
    public void payments(@AuthenticationPrincipal TenantUserDetails user, @RequestParam(name = "from", required = false) String from,
                           @RequestParam(name = "to", required = false) String to, HttpServletResponse response) throws IOException {
        Range range = range(user, from, to);
        if (!begin(user, response, "payments", range, exports.countPayments(user.tenantId(), range.from, range.to))) {
            return;
        }
        BoardRepository.TenantInfo tenant = tenantOf(user);
        Writer out = response.getWriter();
        out.write('\uFEFF');
        out.write(CsvWriter.line(List.of(CsvWriter.text("id"), CsvWriter.text("recorded_at_utc"), CsvWriter.text("paid_at_utc"), CsvWriter.text("status"),
                CsvWriter.text("provider"), CsvWriter.text("transaction_id"), CsvWriter.text("sender"), CsvWriter.text("phone"), CsvWriter.text("reference"),
                CsvWriter.text("amount"), CsvWriter.text("currency"), CsvWriter.text("matched_order_id"))));
        exports.payments(user.tenantId(), range.from, range.to, maxRows, (PaymentRow r) -> {
            List<String> cells = new ArrayList<>();
            cells.add(CsvWriter.text(r.id().toString()));
            cells.add(CsvWriter.text(r.at().toString()));
            cells.add(CsvWriter.text(r.paidAt() == null ? null : r.paidAt().toString()));
            cells.add(CsvWriter.text(r.status()));
            cells.add(CsvWriter.text(r.provider()));
            cells.add(CsvWriter.text(r.txId()));
            cells.add(CsvWriter.text(r.who()));
            cells.add(CsvWriter.text(maskedPhone(r.last4())));
            cells.add(CsvWriter.text(r.ref()));
            cells.add(r.amountMinor() == null ? "" : CsvWriter.number(r.amountMinor(), tenant.scale()));
            cells.add(CsvWriter.text(r.currency()));
            cells.add(CsvWriter.text(r.orderId() == null ? null : r.orderId().toString()));
            write(out, cells);
        });
        out.flush();
    }

    @GetMapping("/app/export/ledger.csv")
    @PreAuthorize("hasAuthority('EXPORT_DATA') and hasAuthority('VIEW_LEDGER')")
    public void ledger(@AuthenticationPrincipal TenantUserDetails user, @RequestParam(name = "from", required = false) String from,
                         @RequestParam(name = "to", required = false) String to, HttpServletResponse response) throws IOException {
        Range range = range(user, from, to);
        if (!begin(user, response, "ledger", range, exports.countLedger(user.tenantId(), range.from, range.to))) {
            return;
        }
        BoardRepository.TenantInfo tenant = tenantOf(user);
        Writer out = response.getWriter();
        out.write('\uFEFF');
        out.write(CsvWriter.line(List.of(CsvWriter.text("transaction_id"), CsvWriter.text("created_at_utc"), CsvWriter.text("kind"),
                CsvWriter.text("reference_type"), CsvWriter.text("reference_id"), CsvWriter.text("account_code"), CsvWriter.text("account_name"),
                CsvWriter.text("debit"), CsvWriter.text("credit"), CsvWriter.text("currency"))));
        exports.ledger(user.tenantId(), range.from, range.to, maxRows, (LedgerRow r) -> {
            List<String> cells = new ArrayList<>();
            cells.add(CsvWriter.text(r.txnId().toString()));
            cells.add(CsvWriter.text(r.at().toString()));
            cells.add(CsvWriter.text(r.kind()));
            cells.add(CsvWriter.text(r.refType()));
            cells.add(CsvWriter.text(r.refId().toString()));
            cells.add(CsvWriter.text(r.accountCode()));
            cells.add(CsvWriter.text(r.accountName()));
            cells.add(CsvWriter.number(r.debitMinor(), tenant.scale()));
            cells.add(CsvWriter.number(r.creditMinor(), tenant.scale()));
            cells.add(CsvWriter.text(tenant.currency()));
            write(out, cells);
        });
        out.flush();
    }

    // ── Shared ─────────────────────────────────────────────────────────────────

    private record Range(Instant from, Instant to, LocalDate today) {
    }

    private Range range(TenantUserDetails user, String from, String to) {
        BoardRepository.TenantInfo tenant = tenantOf(user);
        LocalDate today = LocalDate.now(clock.withZone(tenant.zone()));
        LocalDate last = to == null ? today : date(to);
        LocalDate first = from == null ? last.minusDays(DEFAULT_DAYS - 1) : date(from);
        if (first.isAfter(last) || ChronoUnit.DAYS.between(first, last) + 1 > MAX_DAYS || last.isAfter(today) || first.getYear() < 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return new Range(first.atStartOfDay(tenant.zone()).toInstant(), last.plusDays(1).atStartOfDay(tenant.zone()).toInstant(), today);
    }

    /** An ISO date (yyyy-mm-dd). A longer form the parser accepts (a signed year above 9999) is in the future and refused by the range check. */
    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Checks the limit and the size, writes the audit row and the headers. Returns false if the range is too large (the user was sent back to
     * the export page and nothing else is sent), true when the body may be written.
     */
    private boolean begin(TenantUserDetails user, HttpServletResponse response, String file, Range range, long rows) throws IOException {
        RateDecision decision = limits.export(user.tenantId());
        if (!decision.allowed()) {
            response.setHeader("Retry-After", Long.toString(Math.max(1, decision.retryAfterSeconds())));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        if (rows > maxRows) {
            response.sendRedirect("/app/export?n=" + Notice.EXPORT_TOO_LARGE.name());
            return false;
        }
        audit.append(user.tenantId(), user.userId(), "EXPORT_CSV", file + " rows=" + rows);
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // The name is built from the file kind and the day only: no stored text reaches a header.
        response.setHeader("Content-Disposition", "attachment; filename=\"" + file + "-" + range.today + ".csv\"");
        return true;
    }

    private BoardRepository.TenantInfo tenantOf(TenantUserDetails user) {
        return board.tenant(user.tenantId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String maskedPhone(String last4) {
        return last4 == null ? null : "****" + last4;
    }

    private static void write(Writer out, List<String> cells) {
        try {
            out.write(CsvWriter.line(cells));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
