package dev.chatledger.matching;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What must be true of a tenant's books after any sequence of payments, orders, matches and seller decisions, read straight from the
 * tables as the schema owner (independently of the service and of the database's own triggers):
 *
 * <ol>
 *   <li>no payment is assigned more than its amount, and none to two orders; no order is assigned more than its total;</li>
 *   <li>the ledger is balanced (debits equal credits), and every posting of a match belongs to an active match or has been reversed;</li>
 *   <li>receivables equal the totals of the accepted orders minus the active matches; sales equal the accepted totals; customer credit
 *       equals the excess of the active matches; the clearing account equals what the active matches received;</li>
 *   <li>statuses agree: an order is the function of its active matches, a payment is {@code MATCHED} exactly when it has one, and a
 *       payment that is {@code UNMATCHED}, {@code DUPLICATE} or {@code UNPARSED} has none.</li>
 * </ol>
 * All numbers are minor units.
 */
final class MatchInvariants {

    private MatchInvariants() {
    }

    /** Throws an {@link AssertionError} naming every violated rule. */
    static void assertHold(UUID tenant) {
        List<String> violations = check(tenant);
        if (!violations.isEmpty()) {
            throw new AssertionError("The books of tenant " + tenant + " break " + violations.size() + " rule(s):\n - " + String.join("\n - ", violations));
        }
    }

    static List<String> check(UUID tenant) {
        List<String> v = new ArrayList<>();
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection()) {
            // 1. never more than a payment, never more than an order total
            for (String row : rows(c, "SELECT m.id::text || ' ' || m.amount_minor || '+' || m.excess_minor || ' of ' || p.amount_minor FROM match m "
                    + "JOIN payment p ON p.id = m.payment_id AND p.tenant_id = m.tenant_id WHERE m.tenant_id = ? AND m.state IN ('AUTO', 'CONFIRMED') "
                    + "AND m.amount_minor + m.excess_minor <> p.amount_minor", tenant)) {
                v.add("a match does not use its payment whole: " + row);
            }
            for (String row : rows(c, "SELECT payment_id::text || ' has ' || count(*) FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED') "
                    + "GROUP BY payment_id HAVING count(*) > 1", tenant)) {
                v.add("a payment is matched twice: " + row);
            }
            for (String row : rows(c, "SELECT o.id::text || ' applied ' || a.s || ' of ' || o.total_minor FROM orders o JOIN ("
                    + "SELECT order_id, SUM(amount_minor) AS s FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED') GROUP BY order_id) a "
                    + "ON a.order_id = o.id WHERE o.tenant_id = ? AND a.s > o.total_minor", tenant, tenant)) {
                v.add("an order is assigned more than its total: " + row);
            }
            // 2. the ledger
            long trial = one(c, "SELECT COALESCE(SUM(e.debit_minor) - SUM(e.credit_minor), 0) FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id WHERE t.tenant_id = ?", tenant);
            if (trial != 0) {
                v.add("the trial balance is " + trial);
            }
            long livePostings = one(c, "SELECT count(*) FROM ledger_txn t WHERE t.tenant_id = ? AND t.kind = 'PAYMENT_MATCHED' "
                    + "AND NOT EXISTS (SELECT 1 FROM ledger_txn r WHERE r.tenant_id = t.tenant_id AND r.reverses_txn_id = t.id)", tenant);
            long active = one(c, "SELECT count(*) FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED')", tenant);
            if (livePostings != active) {
                v.add(livePostings + " payment postings are not reversed but " + active + " matches are active");
            }
            for (String row : rows(c, "SELECT m.id::text FROM match m WHERE m.tenant_id = ? AND m.state = 'REVERSED' AND NOT EXISTS ("
                    + "SELECT 1 FROM ledger_txn r WHERE r.tenant_id = m.tenant_id AND r.reverses_txn_id = m.ledger_txn_id)", tenant)) {
                v.add("a reversed match has no reversal posting: " + row);
            }
            // 3. receivables, sales, customer credit, clearing
            long accepted = one(c, "SELECT COALESCE(SUM(o.total_minor), 0) FROM orders o WHERE o.tenant_id = ? AND EXISTS ("
                    + "SELECT 1 FROM ledger_txn t WHERE t.tenant_id = o.tenant_id AND t.kind = 'ORDER_ACCEPTED' AND t.ref_id = o.id)", tenant);
            long applied = one(c, "SELECT COALESCE(SUM(amount_minor), 0) FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED')", tenant);
            long excess = one(c, "SELECT COALESCE(SUM(excess_minor), 0) FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED')", tenant);
            long receivable = balance(c, tenant, "1100", true);
            long sales = balance(c, tenant, "4000", false);
            long credit = balance(c, tenant, "2100", false);
            long clearing = balance(c, tenant, "1000", true);
            if (receivable != accepted - applied) {
                v.add("receivables are " + receivable + " but accepted orders " + accepted + " minus active matches " + applied + " is " + (accepted - applied));
            }
            if (sales != accepted) {
                v.add("sales are " + sales + " but accepted orders total " + accepted);
            }
            if (credit != excess) {
                v.add("customer credit is " + credit + " but the active matches have an excess of " + excess);
            }
            if (clearing != applied + excess) {
                v.add("the clearing account is " + clearing + " but the active matches received " + (applied + excess));
            }
            // 4. statuses
            for (String row : rows(c, "SELECT o.id::text || ' ' || o.status || ' applied ' || COALESCE(a.s, 0) || ' of ' || o.total_minor FROM orders o "
                    + "LEFT JOIN (SELECT order_id, SUM(amount_minor) AS s FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED') GROUP BY order_id) a ON a.order_id = o.id "
                    + "WHERE o.tenant_id = ? AND o.status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID', 'PAID') AND o.status <> CASE "
                    + "WHEN COALESCE(a.s, 0) = 0 THEN 'PENDING_PAYMENT' WHEN COALESCE(a.s, 0) < o.total_minor THEN 'PARTIALLY_PAID' ELSE 'PAID' END", tenant, tenant)) {
                v.add("an order status disagrees with its matches: " + row);
            }
            for (String row : rows(c, "SELECT o.id::text || ' ' || o.status FROM orders o WHERE o.tenant_id = ? AND o.status NOT IN ('PENDING_PAYMENT', 'PARTIALLY_PAID', 'PAID') "
                    + "AND EXISTS (SELECT 1 FROM match m WHERE m.order_id = o.id AND m.state IN ('AUTO', 'CONFIRMED'))", tenant)) {
                v.add("an order that cannot be paid has an active match: " + row);
            }
            for (String row : rows(c, "SELECT p.id::text || ' ' || p.status FROM payment p WHERE p.tenant_id = ? AND (p.status = 'MATCHED') <> EXISTS ("
                    + "SELECT 1 FROM match m WHERE m.payment_id = p.id AND m.state IN ('AUTO', 'CONFIRMED'))", tenant)) {
                v.add("a payment status disagrees with its matches: " + row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return v;
    }

    /** A fingerprint of everything a seller's decision may change, to prove that a repeated or refused decision changed nothing. */
    static String fingerprint(UUID tenant) {
        List<String> parts = new ArrayList<>();
        parts.add("ledger=" + MatchingFixture.ledger(tenant));
        parts.add("txns=" + MatchingFixture.count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ?", tenant, null));
        parts.add("matches=" + MatchingFixture.rows("SELECT id || ' ' || state || ' ' || COALESCE(amount_minor::text, '-') || ' ' || COALESCE(decided_by::text, '-') "
                + "FROM match WHERE tenant_id = ? ORDER BY id", tenant));
        parts.add("orders=" + MatchingFixture.rows("SELECT id || ' ' || status FROM orders WHERE tenant_id = ? ORDER BY id", tenant));
        parts.add("payments=" + MatchingFixture.rows("SELECT id || ' ' || status FROM payment WHERE tenant_id = ? ORDER BY id", tenant));
        parts.add("audit=" + MatchingFixture.count("SELECT count(*) FROM audit_log WHERE tenant_id = ?", tenant, null));
        parts.add("flags=" + MatchingFixture.rows("SELECT code || ' ' || subject_type || ' ' || COALESCE(payment_id::text, '-') || ' ' || COALESCE(order_id::text, '-') "
                + "|| ' ' || (resolved_at IS NOT NULL) FROM flag WHERE tenant_id = ? ORDER BY 1", tenant));
        return String.join("\n", parts);
    }

    /** Debit minus credit for an asset account ({@code debitNormal}), credit minus debit for the others. */
    private static long balance(Connection c, UUID tenant, String code, boolean debitNormal) throws SQLException {
        long net = one(c, "SELECT COALESCE(SUM(e.debit_minor) - SUM(e.credit_minor), 0) FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id "
                + "JOIN account a ON a.id = e.account_id WHERE t.tenant_id = ? AND a.code = ?", tenant, code);
        return debitNormal ? net : -net;
    }

    private static long one(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static List<String> rows(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
