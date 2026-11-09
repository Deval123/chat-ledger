package dev.chatledger.board;

import dev.chatledger.tenancy.DbTime;
import dev.chatledger.tenancy.TenantScope;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the board pages read. Read-only; every statement names the tenant, and the tenant is set on the connection as well
 * (row-level security), so a statement that forgot the tenant would still see nothing of another one. Every statement is a complete
 * string literal with bound parameters.
 *
 * <p>Where an item is shown is decided in one place, the {@code CASE} that computes {@code col}: each order or payment is in exactly one
 * column (design D1). The raw text of a message is read only when the caller asks for it ({@code withRaw}) and only for the kinds of
 * item whose detail page may show it (design D10); a helper never asks.
 */
@Repository
public class BoardRepository {

    /** The columns of the board, in the order they are shown. {@code ASIDE} is not a column: it is behind a link. */
    public enum Column {
        AWAITING, REVIEW, MATCHED, FLAGGED, UNPARSED, ASIDE;

        /** The column named by a path segment (lower case), or empty. */
        public static Optional<Column> of(String name) {
            for (Column column : values()) {
                if (column.name().toLowerCase(java.util.Locale.ROOT).equals(name)) {
                    return Optional.of(column);
                }
            }
            return Optional.empty();
        }

        public String path() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum Kind { ORDER, PAYMENT }

    /** One card. Amounts are minor units; {@code amountMinor} is null for an item that could not be read. */
    public record Card(Kind kind, UUID id, String status, Long amountMinor, long appliedMinor, long suggestions, UUID suggestionId, String currency,
                       String who, String last4, String ref, Instant at, boolean flagged, UUID flagId, String flagCode, long openFlags) {
        @Override
        public String toString() {
            return "Card[" + kind + " " + id + " " + status + "]";
        }
    }

    /** Where a page ends: the time and id of its last card. */
    public record Cursor(Instant at, UUID id) {
        /** {@code <microseconds since epoch>_<uuid>}, or empty for anything else. Strict: digits, one underscore, a canonical UUID. */
        public static Optional<Cursor> parse(String text) {
            if (text == null) {
                return Optional.empty();
            }
            int underscore = text.indexOf('_');
            if (underscore < 1 || underscore > 18 || text.length() - underscore - 1 != 36) {
                return Optional.empty();
            }
            long micros = 0;
            for (int i = 0; i < underscore; i++) {
                char ch = text.charAt(i);
                if (ch < '0' || ch > '9') {
                    return Optional.empty();
                }
                micros = micros * 10 + (ch - '0');
            }
            String uuidText = text.substring(underscore + 1);
            for (int i = 0; i < 36; i++) {
                char ch = uuidText.charAt(i);
                boolean dash = i == 8 || i == 13 || i == 18 || i == 23;
                boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
                if (dash ? ch != '-' : !hex) {
                    return Optional.empty();
                }
            }
            return Optional.of(new Cursor(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L),
                    UUID.fromString(uuidText)));
        }

        public String format() {
            long micros = at.getEpochSecond() * 1_000_000L + at.getNano() / 1000;
            return micros + "_" + id;
        }
    }

    /** A page of cards and, when there may be more, the cursor to ask for the next. */
    public record Page(List<Card> cards, Cursor next) {
    }

    public record TenantInfo(String name, String currency, int scale, ZoneId zone) {
    }

    public record Line(String label, int qty, Long unitMinor) {
    }

    /** A flag as a detail page shows it. {@code pair} is true for a flag about one pair (a payment and one order). */
    public record FlagView(UUID id, String code, Instant at, Instant resolvedAt, UUID resolvedBy, boolean pair, UUID paymentId, UUID orderId) {
        public boolean open() {
            return resolvedAt == null;
        }
    }

    /** A match as shown on a detail page, with the item on the other side. */
    public record MatchView(UUID id, String state, Long amountMinor, long excessMinor, int score, String reasonsJson, Instant decidedAt,
                            UUID decidedBy, UUID otherId, String otherWho, String otherRef, Long otherAmountMinor, String otherStatus,
                            long otherAppliedMinor) {
    }

    public record OrderDetail(UUID id, String status, Long totalMinor, long appliedMinor, String currency, String who, String last4,
                              String ref, String deliveryNote, Instant at, boolean flagged, String rawText, List<Line> lines,
                              List<MatchView> matches, List<FlagView> flags) {
    }

    public record PaymentDetail(UUID id, String status, long amountMinor, boolean hasAmount, String currency, String provider, String txId,
                                String who, String last4, String ref, Instant paidAt, Instant at, boolean flagged, String rawText,
                                UUID duplicateOf, List<MatchView> matches, List<FlagView> flags) {
    }

    public record SummaryCounts(long orders, long paid, long flagged) {
    }

    public record SummaryAmounts(long outstandingMinor, long collectedMinor, long unverifiedCount, long unverifiedMinor) {
    }

    private final TenantScope scope;

    public BoardRepository(DataSource dataSource) {
        this.scope = new TenantScope(dataSource);
    }

    // ── Tenant ─────────────────────────────────────────────────────────────────

    public Optional<TenantInfo> tenant(UUID tenantId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT name, currency, scale, timezone FROM tenant WHERE id = ? AND disabled_at IS NULL")) {
                    ps.setObject(1, tenantId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return Optional.<TenantInfo>empty();
                        }
                        ZoneId zone;
                        try {
                            zone = ZoneId.of(rs.getString("timezone"));
                        } catch (RuntimeException unknown) {
                            zone = ZoneId.of("UTC");   // a zone the Java runtime does not know: fall back to UTC, never fail the page
                        }
                        return Optional.of(new TenantInfo(rs.getString("name"), rs.getString("currency").strip(), rs.getInt("scale"), zone));
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the tenant", e);
        }
    }

    // ── Counts and columns ─────────────────────────────────────────────────────

    /** How many items are in each column. A column with none is present with 0. */
    public Map<Column, Long> counts(UUID tenantId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "WITH items AS ("
                                + "SELECT o.status AS status, EXISTS (SELECT 1 FROM flag f WHERE f.tenant_id = o.tenant_id AND f.subject_type = 'ORDER' AND f.order_id = o.id "
                                + "AND f.resolved_at IS NULL) AS flagged FROM orders o WHERE o.tenant_id = ? "
                                + "UNION ALL "
                                + "SELECT p.status, EXISTS (SELECT 1 FROM flag f WHERE f.tenant_id = p.tenant_id AND f.payment_id = p.id "
                                + "AND f.resolved_at IS NULL) FROM payment p WHERE p.tenant_id = ?) "
                                + "SELECT CASE WHEN status = 'UNPARSED' THEN 'UNPARSED' "
                                + "WHEN flagged THEN 'FLAGGED' "
                                + "WHEN status IN ('NEEDS_REVIEW', 'RECEIVED', 'UNVERIFIED') THEN 'REVIEW' "
                                + "WHEN status = 'PAID' THEN 'MATCHED' "
                                + "WHEN status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID') THEN 'AWAITING' "
                                + "WHEN status IN ('UNMATCHED', 'DUPLICATE') THEN 'ASIDE' END AS col, count(*) AS n FROM items GROUP BY 1")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, tenantId);
                    Map<Column, Long> out = new EnumMap<>(Column.class);
                    for (Column column : Column.values()) {
                        out.put(column, 0L);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String col = rs.getString("col");
                            if (col != null) {
                                out.put(Column.valueOf(col), rs.getLong("n"));
                            }
                        }
                    }
                    return out;
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count the board", e);
        }
    }

    /** One page of a column, newest first, after {@code after} (null for the first page). {@code size} is cut to 1..50. */
    public Page column(UUID tenantId, Column column, Cursor after, int size) {
        int limit = Math.max(1, Math.min(50, size));
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "WITH items AS ("
                                + "SELECT CAST('ORDER' AS TEXT) AS kind, o.id AS id, o.status AS status, o.total_minor AS amount, o.currency AS currency, "
                                + "o.created_at AS ts, o.order_ref AS ref, cu.display_name AS who, cu.phone_last4 AS last4, "
                                + "EXISTS (SELECT 1 FROM flag f WHERE f.tenant_id = o.tenant_id AND f.subject_type = 'ORDER' AND f.order_id = o.id AND f.resolved_at IS NULL) AS flagged "
                                + "FROM orders o LEFT JOIN customer cu ON cu.id = o.customer_id AND cu.tenant_id = o.tenant_id WHERE o.tenant_id = ? "
                                + "UNION ALL "
                                + "SELECT 'PAYMENT', p.id, p.status, p.amount_minor, p.currency, p.created_at, COALESCE(p.tx_id, p.reference), "
                                + "p.sender_name, p.sender_last4, EXISTS (SELECT 1 FROM flag f WHERE f.tenant_id = p.tenant_id AND f.payment_id = p.id "
                                + "AND f.resolved_at IS NULL) FROM payment p WHERE p.tenant_id = ?), "
                                + "placed AS (SELECT i.*, CASE WHEN i.status = 'UNPARSED' THEN 'UNPARSED' "
                                + "WHEN i.flagged THEN 'FLAGGED' "
                                + "WHEN i.status IN ('NEEDS_REVIEW', 'RECEIVED', 'UNVERIFIED') THEN 'REVIEW' "
                                + "WHEN i.status = 'PAID' THEN 'MATCHED' "
                                + "WHEN i.status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID') THEN 'AWAITING' "
                                + "WHEN i.status IN ('UNMATCHED', 'DUPLICATE') THEN 'ASIDE' END AS col FROM items i), "
                                + "page AS (SELECT * FROM placed WHERE col = ? AND (CAST(? AS TIMESTAMPTZ) IS NULL "
                                + "OR (ts, id) < (CAST(? AS TIMESTAMPTZ), CAST(? AS UUID))) ORDER BY ts DESC, id DESC LIMIT ?) "
                                + "SELECT g.kind, g.id, g.status, g.amount, g.currency, g.ts, g.ref, g.who, g.last4, g.flagged, "
                                + "COALESCE((SELECT SUM(m.amount_minor) FROM match m WHERE m.tenant_id = ? AND m.order_id = g.id "
                                + "AND m.state IN ('AUTO', 'CONFIRMED')), 0) AS applied, "
                                + "(SELECT count(*) FROM match m WHERE m.tenant_id = ? AND m.state = 'SUGGESTED' "
                                + "AND (m.payment_id = g.id OR m.order_id = g.id)) AS suggestions, "
                                + "CASE WHEN g.kind = 'PAYMENT' THEN (SELECT m.id FROM match m WHERE m.tenant_id = ? AND m.payment_id = g.id "
                                + "AND m.state = 'SUGGESTED' ORDER BY m.score DESC, m.id LIMIT 1) END AS suggestion_id, fl.flag_id, fl.flag_code, "
                                + "COALESCE(fl.open_flags, 0) AS open_flags "
                                + "FROM page g LEFT JOIN LATERAL (SELECT f.id AS flag_id, f.code AS flag_code, count(*) OVER () AS open_flags FROM flag f "
                                + "WHERE f.tenant_id = ? AND f.resolved_at IS NULL AND ((g.kind = 'PAYMENT' AND f.payment_id = g.id) "
                                + "OR (g.kind = 'ORDER' AND f.subject_type = 'ORDER' AND f.order_id = g.id)) ORDER BY f.created_at, f.id LIMIT 1) fl ON TRUE "
                                + "ORDER BY g.ts DESC, g.id DESC")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, tenantId);
                    ps.setString(3, column.name());
                    bindCursor(ps, 4, after);
                    ps.setInt(7, limit + 1);
                    ps.setObject(8, tenantId);
                    ps.setObject(9, tenantId);
                    ps.setObject(10, tenantId);
                    ps.setObject(11, tenantId);
                    List<Card> cards = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String last4 = rs.getString("last4");
                            long amount = rs.getLong("amount");
                            Long amountOrNull = rs.wasNull() ? null : amount;
                            cards.add(new Card(Kind.valueOf(rs.getString("kind")), rs.getObject("id", UUID.class), rs.getString("status"),
                                    amountOrNull, rs.getLong("applied"), rs.getLong("suggestions"), rs.getObject("suggestion_id", UUID.class), strip(rs.getString("currency")),
                                    rs.getString("who"), last4 == null ? null : last4.strip(), rs.getString("ref"),
                                    rs.getObject("ts", OffsetDateTime.class).toInstant(), rs.getBoolean("flagged"),
                                    rs.getObject("flag_id", UUID.class), rs.getString("flag_code"), rs.getLong("open_flags")));
                        }
                    }
                    if (cards.size() > limit) {
                        cards = new ArrayList<>(cards.subList(0, limit));
                        Card last = cards.get(limit - 1);
                        return new Page(List.copyOf(cards), new Cursor(last.at(), last.id()));
                    }
                    return new Page(List.copyOf(cards), null);
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the board", e);
        }
    }

    private static void bindCursor(PreparedStatement ps, int first, Cursor after) throws SQLException {
        if (after == null) {
            ps.setNull(first, Types.TIMESTAMP_WITH_TIMEZONE);
            ps.setNull(first + 1, Types.TIMESTAMP_WITH_TIMEZONE);
            ps.setNull(first + 2, Types.OTHER);
        } else {
            ps.setObject(first, DbTime.utc(after.at()));
            ps.setObject(first + 1, DbTime.utc(after.at()));
            ps.setObject(first + 2, after.id());
        }
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    // ── Details ────────────────────────────────────────────────────────────────

    /**
     * One order of the tenant, or empty if it is not this tenant's. {@code withRaw} asks for the message text; it is returned only for
     * an order that needs review, could not be read, or is flagged.
     */
    public Optional<OrderDetail> order(UUID tenantId, UUID orderId, boolean withRaw) {
        try {
            return scope.call(tenantId, c -> {
                OrderDetail head;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT o.id, o.status, o.total_minor, o.currency, o.created_at, o.order_ref, o.delivery_note, cu.display_name, cu.phone_last4, "
                                + "EXISTS (SELECT 1 FROM flag f WHERE f.tenant_id = o.tenant_id AND f.subject_type = 'ORDER' AND f.order_id = o.id AND f.resolved_at IS NULL) AS flagged, "
                                + "COALESCE((SELECT SUM(m.amount_minor) FROM match m WHERE m.tenant_id = o.tenant_id AND m.order_id = o.id "
                                + "AND m.state IN ('AUTO', 'CONFIRMED')), 0) AS applied, "
                                + "CASE WHEN CAST(? AS BOOLEAN) AND (o.status IN ('NEEDS_REVIEW', 'UNPARSED') OR EXISTS (SELECT 1 FROM flag f "
                                + "WHERE f.tenant_id = o.tenant_id AND f.subject_type = 'ORDER' AND f.order_id = o.id AND f.resolved_at IS NULL)) THEN o.raw_text END AS raw "
                                + "FROM orders o LEFT JOIN customer cu ON cu.id = o.customer_id AND cu.tenant_id = o.tenant_id "
                                + "WHERE o.tenant_id = ? AND o.id = ?")) {
                    ps.setBoolean(1, withRaw);
                    ps.setObject(2, tenantId);
                    ps.setObject(3, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return Optional.<OrderDetail>empty();
                        }
                        long total = rs.getLong("total_minor");
                        Long totalOrNull = rs.wasNull() ? null : total;
                        String last4 = rs.getString("phone_last4");
                        head = new OrderDetail(orderId, rs.getString("status"), totalOrNull, rs.getLong("applied"), rs.getString("currency").strip(),
                                rs.getString("display_name"), last4 == null ? null : last4.strip(), rs.getString("order_ref"),
                                rs.getString("delivery_note"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                                rs.getBoolean("flagged"), rs.getString("raw"), List.of(), List.of(), List.of());
                    }
                }
                List<Line> lines = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT label, qty, unit_minor FROM order_line WHERE tenant_id = ? AND order_id = ? ORDER BY id")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long unit = rs.getLong("unit_minor");
                            lines.add(new Line(rs.getString("label"), rs.getInt("qty"), rs.wasNull() ? null : unit));
                        }
                    }
                }
                List<MatchView> matches = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT m.id, m.state, m.amount_minor, m.excess_minor, m.score, m.reasons_json, m.decided_at, m.decided_by, "
                                + "p.id AS other_id, p.sender_name, COALESCE(p.tx_id, p.reference) AS other_ref, p.amount_minor AS other_amount, p.status AS other_status, 0 AS other_applied "
                                + "FROM match m JOIN payment p ON p.id = m.payment_id AND p.tenant_id = m.tenant_id "
                                + "WHERE m.tenant_id = ? AND m.order_id = ? ORDER BY m.created_at, m.id")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, orderId);
                    readMatches(ps, matches);
                }
                return Optional.of(new OrderDetail(head.id(), head.status(), head.totalMinor(), head.appliedMinor(), head.currency(), head.who(),
                        head.last4(), head.ref(), head.deliveryNote(), head.at(), head.flagged(), head.rawText(), List.copyOf(lines),
                        List.copyOf(matches), orderFlags(c, tenantId, orderId)));
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the order", e);
        }
    }

    /** One payment of the tenant, or empty. {@code withRaw}: see {@link #order}. */
    public Optional<PaymentDetail> payment(UUID tenantId, UUID paymentId, boolean withRaw) {
        try {
            return scope.call(tenantId, c -> {
                PaymentDetail head;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT p.id, p.status, p.amount_minor, p.currency, p.provider, p.tx_id, p.sender_name, p.sender_last4, "
                                + "COALESCE(p.reference, '') AS reference, p.paid_at, p.created_at, p.duplicate_of, "
                                + "EXISTS (SELECT 1 FROM flag f WHERE f.tenant_id = p.tenant_id AND f.payment_id = p.id AND f.resolved_at IS NULL) AS flagged, "
                                + "CASE WHEN CAST(? AS BOOLEAN) AND (p.status IN ('UNPARSED', 'DUPLICATE', 'UNVERIFIED') OR EXISTS (SELECT 1 FROM flag f "
                                + "WHERE f.tenant_id = p.tenant_id AND f.payment_id = p.id AND f.resolved_at IS NULL)) THEN p.raw_text END AS raw "
                                + "FROM payment p WHERE p.tenant_id = ? AND p.id = ?")) {
                    ps.setBoolean(1, withRaw);
                    ps.setObject(2, tenantId);
                    ps.setObject(3, paymentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return Optional.<PaymentDetail>empty();
                        }
                        long amount = rs.getLong("amount_minor");
                        boolean hasAmount = !rs.wasNull();
                        String last4 = rs.getString("sender_last4");
                        OffsetDateTime paidAt = rs.getObject("paid_at", OffsetDateTime.class);
                        String reference = rs.getString("reference");
                        String currency = rs.getString("currency");
                        head = new PaymentDetail(paymentId, rs.getString("status"), amount, hasAmount, currency == null ? null : currency.strip(),
                                rs.getString("provider"), rs.getString("tx_id"), rs.getString("sender_name"), last4 == null ? null : last4.strip(),
                                reference == null || reference.isEmpty() ? null : reference, paidAt == null ? null : paidAt.toInstant(),
                                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getBoolean("flagged"), rs.getString("raw"),
                                rs.getObject("duplicate_of", UUID.class), List.of(), List.of());
                    }
                }
                List<MatchView> matches = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT m.id, m.state, m.amount_minor, m.excess_minor, m.score, m.reasons_json, m.decided_at, m.decided_by, "
                                + "o.id AS other_id, cu.display_name AS sender_name, o.order_ref AS other_ref, o.total_minor AS other_amount, o.status AS other_status, COALESCE((SELECT SUM(m2.amount_minor) FROM match m2 "
                                + "WHERE m2.tenant_id = o.tenant_id AND m2.order_id = o.id AND m2.state IN ('AUTO', 'CONFIRMED')), 0) AS other_applied "
                                + "FROM match m JOIN orders o ON o.id = m.order_id AND o.tenant_id = m.tenant_id "
                                + "LEFT JOIN customer cu ON cu.id = o.customer_id AND cu.tenant_id = o.tenant_id "
                                + "WHERE m.tenant_id = ? AND m.payment_id = ? ORDER BY m.created_at, m.id")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, paymentId);
                    readMatches(ps, matches);
                }
                return Optional.of(new PaymentDetail(head.id(), head.status(), head.amountMinor(), head.hasAmount(), head.currency(), head.provider(),
                        head.txId(), head.who(), head.last4(), head.ref(), head.paidAt(), head.at(), head.flagged(), head.rawText(),
                        head.duplicateOf(), List.copyOf(matches), paymentFlags(c, tenantId, paymentId)));
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the payment", e);
        }
    }

    private static void readMatches(PreparedStatement ps, List<MatchView> out) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long amount = rs.getLong("amount_minor");
                Long amountOrNull = rs.wasNull() ? null : amount;
                long other = rs.getLong("other_amount");
                Long otherOrNull = rs.wasNull() ? null : other;
                OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
                out.add(new MatchView(rs.getObject("id", UUID.class), rs.getString("state"), amountOrNull, rs.getLong("excess_minor"),
                        rs.getInt("score"), rs.getString("reasons_json"), decidedAt == null ? null : decidedAt.toInstant(),
                        rs.getObject("decided_by", UUID.class), rs.getObject("other_id", UUID.class), rs.getString("sender_name"),
                        rs.getString("other_ref"), otherOrNull, rs.getString("other_status"), rs.getLong("other_applied")));
            }
        }
    }

    /** Every flag of a payment, open and resolved, oldest first: its own and those of its pairs. */
    private static List<FlagView> paymentFlags(java.sql.Connection c, UUID tenantId, UUID paymentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, code, created_at, resolved_at, resolved_by, order_id IS NOT NULL AS pair, payment_id, order_id FROM flag "
                        + "WHERE tenant_id = ? AND payment_id = ? ORDER BY created_at, id LIMIT 50")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, paymentId);
            return readFlags(ps);
        }
    }

    /** Every flag of an order, open and resolved, oldest first: its own and those of its pairs with payments. */
    private static List<FlagView> orderFlags(java.sql.Connection c, UUID tenantId, UUID orderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, code, created_at, resolved_at, resolved_by, subject_type = 'PAYMENT' AS pair, payment_id, order_id FROM flag "
                        + "WHERE tenant_id = ? AND order_id = ? ORDER BY created_at, id LIMIT 50")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            return readFlags(ps);
        }
    }

    private static List<FlagView> readFlags(PreparedStatement ps) throws SQLException {
        List<FlagView> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                OffsetDateTime resolved = rs.getObject("resolved_at", OffsetDateTime.class);
                out.add(new FlagView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        resolved == null ? null : resolved.toInstant(), rs.getObject("resolved_by", UUID.class), rs.getBoolean("pair"),
                        rs.getObject("payment_id", UUID.class), rs.getObject("order_id", UUID.class)));
            }
        }
        return List.copyOf(out);
    }

    // ── Summary ────────────────────────────────────────────────────────────────

    /** Counts for the day [from, to): orders recorded (parsed ones), how many of those are paid now, and the items flagged now. */
    public SummaryCounts summaryCounts(UUID tenantId, Instant from, Instant to) {
        try {
            return scope.call(tenantId, c -> {
                long orders;
                long paid;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) AS orders, count(*) FILTER (WHERE status = 'PAID') AS paid FROM orders "
                                + "WHERE tenant_id = ? AND status <> 'UNPARSED' AND created_at >= ? AND created_at < ?")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, DbTime.utc(from));
                    ps.setObject(3, DbTime.utc(to));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        orders = rs.getLong("orders");
                        paid = rs.getLong("paid");
                    }
                }
                long flagged;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT (SELECT count(*) FROM payment p WHERE p.tenant_id = ? AND p.status <> 'UNPARSED' "
                                + "AND EXISTS (SELECT 1 FROM flag f WHERE f.tenant_id = p.tenant_id AND f.payment_id = p.id AND f.resolved_at IS NULL)) "
                                + "+ (SELECT count(*) FROM orders o WHERE o.tenant_id = ? AND o.status <> 'UNPARSED' AND EXISTS (SELECT 1 FROM flag f "
                                + "WHERE f.tenant_id = o.tenant_id AND f.subject_type = 'ORDER' AND f.order_id = o.id AND f.resolved_at IS NULL)) AS n")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, tenantId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        flagged = rs.getLong("n");
                    }
                }
                return new SummaryCounts(orders, paid, flagged);
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the summary", e);
        }
    }

    /**
     * Money for the day [from, to): what is still owed on all open orders now, what was collected (payments with a transaction id,
     * paid in the day, not a repeat), and the unverified payments of the day, counted apart.
     */
    public SummaryAmounts summaryAmounts(UUID tenantId, Instant from, Instant to) {
        try {
            return scope.call(tenantId, c -> {
                long outstanding;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COALESCE(SUM(o.total_minor - COALESCE((SELECT SUM(m.amount_minor) FROM match m WHERE m.tenant_id = o.tenant_id "
                                + "AND m.order_id = o.id AND m.state IN ('AUTO', 'CONFIRMED')), 0)), 0) AS owed FROM orders o "
                                + "WHERE o.tenant_id = ? AND o.status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID')")) {
                    ps.setObject(1, tenantId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        outstanding = rs.getLong("owed");
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COALESCE(SUM(amount_minor) FILTER (WHERE tx_id IS NOT NULL AND status IN ('RECEIVED', 'MATCHED', 'UNMATCHED')), 0) AS collected, "
                                + "count(*) FILTER (WHERE status = 'UNVERIFIED') AS unverified_n, "
                                + "COALESCE(SUM(amount_minor) FILTER (WHERE status = 'UNVERIFIED'), 0) AS unverified_sum "
                                + "FROM payment WHERE tenant_id = ? AND paid_at >= ? AND paid_at < ?")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, DbTime.utc(from));
                    ps.setObject(3, DbTime.utc(to));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return new SummaryAmounts(outstanding, rs.getLong("collected"), rs.getLong("unverified_n"), rs.getLong("unverified_sum"));
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the summary", e);
        }
    }
}
