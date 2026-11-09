package dev.chatledger.board;

import dev.chatledger.tenancy.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The reconciliation check on the books: six comparisons between the ledger and the orders and matches, run on demand and cached for five
 * minutes per tenant. A mismatch is logged with fixed labels (no amounts, no names), is never hidden, and stays visible ({@link #lastKnown})
 * until a later check passes. Nothing here changes data.
 *
 * <p>The six comparisons: (1) debits equal credits; (2) accounts receivable equals the accepted orders less the active matches; (3) sales equal
 * the accepted orders; (4) customer credit equals the excess of the active matches; (5) the clearing account equals what the active matches
 * received; (6) the postings of matches that were not reversed are exactly as many as the active matches.
 */
@Component
public class BooksCheck {

    private static final Logger LOG = LoggerFactory.getLogger("books.check");
    static final Duration FRESH_FOR = Duration.ofMinutes(5);
    private static final int MAX_TENANTS = 10_000;

    /** Which comparison a line is. */
    public enum Code { TRIAL_BALANCE, RECEIVABLE, SALES, CUSTOMER_CREDIT, CLEARING, MATCH_POSTINGS }

    /** Both sides of one comparison. */
    public record Line(Code code, long left, long right) {
        public boolean ok() {
            return left == right;
        }
    }

    public record Result(Instant checkedAt, List<Line> lines) {
        public boolean balanced() {
            return lines.stream().allMatch(Line::ok);
        }
    }

    private final TenantScope scope;
    private final Clock clock;
    private final Map<UUID, Result> cache = new ConcurrentHashMap<>();

    public BooksCheck(DataSource dataSource, Clock clock) {
        this.scope = new TenantScope(dataSource);
        this.clock = clock;
    }

    /** The cached result if it is under five minutes old (and {@code fresh} is false); otherwise a new run. */
    public Result check(UUID tenantId, boolean fresh) {
        Result known = cache.get(tenantId);
        if (!fresh && known != null && Duration.between(known.checkedAt(), clock.instant()).compareTo(FRESH_FOR) < 0) {
            return known;
        }
        Result result = run(tenantId);
        if (cache.size() >= MAX_TENANTS) {
            cache.clear();
        }
        cache.put(tenantId, result);
        if (!result.balanced()) {
            LOG.error("books do not balance tenant={} failing={}", tenantId, result.lines().stream().filter(l -> !l.ok()).map(l -> l.code().name()).toList());
        }
        return result;
    }

    /** The last result without running anything: empty if no check has run since the application started. */
    public Optional<Result> lastKnown(UUID tenantId) {
        return Optional.ofNullable(cache.get(tenantId));
    }

    private Result run(UUID tenantId) {
        try {
            return scope.call(tenantId, c -> {
                long trial = one(c.prepareStatement("SELECT COALESCE(SUM(e.debit_minor) - SUM(e.credit_minor), 0) FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id "
                        + "WHERE t.tenant_id = ?"), tenantId);
                long accepted = one(c.prepareStatement("SELECT COALESCE(SUM(o.total_minor), 0) FROM orders o WHERE o.tenant_id = ? AND EXISTS ("
                        + "SELECT 1 FROM ledger_txn t WHERE t.tenant_id = o.tenant_id AND t.kind = 'ORDER_ACCEPTED' AND t.ref_id = o.id)"), tenantId);
                long applied = one(c.prepareStatement("SELECT COALESCE(SUM(amount_minor), 0) FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED')"), tenantId);
                long excess = one(c.prepareStatement("SELECT COALESCE(SUM(excess_minor), 0) FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED')"), tenantId);
                long receivable = one(c.prepareStatement("SELECT COALESCE(SUM(e.debit_minor) - SUM(e.credit_minor), 0) FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id "
                        + "JOIN account a ON a.id = e.account_id AND a.tenant_id = t.tenant_id WHERE t.tenant_id = ? AND a.code = '1100'"), tenantId);
                long sales = one(c.prepareStatement("SELECT COALESCE(SUM(e.credit_minor) - SUM(e.debit_minor), 0) FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id "
                        + "JOIN account a ON a.id = e.account_id AND a.tenant_id = t.tenant_id WHERE t.tenant_id = ? AND a.code = '4000'"), tenantId);
                long credit = one(c.prepareStatement("SELECT COALESCE(SUM(e.credit_minor) - SUM(e.debit_minor), 0) FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id "
                        + "JOIN account a ON a.id = e.account_id AND a.tenant_id = t.tenant_id WHERE t.tenant_id = ? AND a.code = '2100'"), tenantId);
                long clearing = one(c.prepareStatement("SELECT COALESCE(SUM(e.debit_minor) - SUM(e.credit_minor), 0) FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id "
                        + "JOIN account a ON a.id = e.account_id AND a.tenant_id = t.tenant_id WHERE t.tenant_id = ? AND a.code = '1000'"), tenantId);
                long livePostings = one(c.prepareStatement("SELECT count(*) FROM ledger_txn t WHERE t.tenant_id = ? AND t.kind = 'PAYMENT_MATCHED' "
                        + "AND NOT EXISTS (SELECT 1 FROM ledger_txn r WHERE r.tenant_id = t.tenant_id AND r.reverses_txn_id = t.id)"), tenantId);
                long active = one(c.prepareStatement("SELECT count(*) FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED')"), tenantId);
                List<Line> lines = new ArrayList<>();
                lines.add(new Line(Code.TRIAL_BALANCE, trial, 0));
                lines.add(new Line(Code.RECEIVABLE, receivable, accepted - applied));
                lines.add(new Line(Code.SALES, sales, accepted));
                lines.add(new Line(Code.CUSTOMER_CREDIT, credit, excess));
                lines.add(new Line(Code.CLEARING, clearing, applied + excess));
                lines.add(new Line(Code.MATCH_POSTINGS, livePostings, active));
                return new Result(clock.instant(), List.copyOf(lines));
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check the books", e);
        }
    }

    /** Runs one single-value query; the statement is created at the call site, where the SQL is a literal. */
    private static long one(PreparedStatement statement, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = statement) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
