package dev.chatledger.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared helpers of the board integration tests: forms with a CSRF token, the database as the owner sees it, synthetic messages. */
abstract class BoardIT extends AbstractWebIT {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** The path (and query) of a redirect: the tests talk to one host. */
    static String path(Browser.Response r) {
        return r.header("location").replaceFirst("^https?://[^/]+", "");
    }

    /** A form post with the CSRF token of the current session. */
    Browser.Response post(Browser b, String path, Map<String, String> form) {
        java.util.Map<String, String> all = new java.util.LinkedHashMap<>(form);
        all.put("_csrf", Browser.csrf(b.get("/app")));
        return b.post(path, all);
    }

    /** The same post the way htmx sends it: a header, not a field. */
    Browser.Response htmx(Browser b, String path, Map<String, String> form) {
        String token = Browser.csrf(b.get("/app"));
        return b.post(path, form, "HX-Request", "true", "X-CSRF-TOKEN", token);
    }

    Browser.Response pasteOrder(Browser b, String text) {
        return post(b, "/app/orders", Map.of("mid", UUID.randomUUID().toString(), "text", text));
    }

    Browser.Response pastePayment(Browser b, String text) {
        return post(b, "/app/payments", Map.of("mid", UUID.randomUUID().toString(), "text", text));
    }

    static String orderText(String ref, String name, String phone, long total) {
        return "Order: " + ref + "\nName: " + name + "\nPhone: " + phone + "\n1 x rice @ " + total;
    }

    String sms(long amount, String name, String phone, String ref) {
        String date = DATE.format(clock.instant().minus(Duration.ofMinutes(5)).atOffset(ZoneOffset.ofHours(1)));
        return "You received " + amount + " XAF from " + name + " (" + phone + "). Txn TX" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                + (ref == null ? "" : ". Ref " + ref) + ". " + date;
    }

    /** One value from the database, read as the schema owner. */
    String one(String sql, Object... args) {
        List<String> rows = rows(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    List<String> rows(String sql, Object... args) {
        try (Connection c = DB.migrateDataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    UUID orderId(UUID tenant, String ref) {
        return UUID.fromString(one("SELECT id::text FROM orders WHERE tenant_id = ? AND order_ref = ?", tenant, ref));
    }

    /** The first value captured by the pattern, or null. */
    static String find(String pattern, String text) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** A balanced posting of a match that does not exist, written by the schema owner: the trial balance holds, the books do not agree. */
    void forgePosting(UUID tenant) {
        try (Connection c = DB.migrateDataSource().getConnection()) {
            c.setAutoCommit(false);
            UUID txn = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'PAYMENT_MATCHED', 'MATCH', ?)")) {
                ps.setObject(1, txn);
                ps.setObject(2, tenant);
                ps.setObject(3, UUID.randomUUID());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) "
                    + "SELECT gen_random_uuid(), ?, id, CASE WHEN code = '1000' THEN 100 ELSE 0 END, CASE WHEN code = '1100' THEN 100 ELSE 0 END "
                    + "FROM account WHERE tenant_id = ? AND code IN ('1000', '1100')")) {
                ps.setObject(1, txn);
                ps.setObject(2, tenant);
                ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
