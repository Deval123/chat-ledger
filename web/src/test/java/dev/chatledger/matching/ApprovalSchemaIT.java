package dev.chatledger.matching;

import dev.chatledger.tenancy.TenantScope;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V018, as the database enforces it whoever calls: an order that needs review gets its total from the seller and becomes payable, and that
 * is the only time a total may change; the order must then have its ORDER_ACCEPTED posting by commit; the bounds of the total are the
 * database's too. Run as the application role (what the application is) and as the schema owner (which the triggers bind as well).
 *
 * <p>Requirements: 4.1, 4.4, 6, 9.1. Task 8.
 */
class ApprovalSchemaIT {

    private final MatchWorld w = new MatchWorld();

    private interface Sql {
        void run(Connection c) throws SQLException;
    }

    /** Runs the work as the application role in the tenant, in one transaction that commits (deferred triggers fire at the commit). */
    private void asApp(Sql work) throws SQLException {
        new TenantScope(MatchWorld.DB.appDataSource()).call(w.tenant, c -> {
            work.run(c);
            return null;
        });
    }

    private void asOwner(Sql work) throws SQLException {
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                work.run(c);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static void sql(Connection c, String sql, Object... params) throws SQLException {
        MatchWorld.run(c, sql, params);
    }

    private static String stringOf(String sql, Object... params) throws SQLException {
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    /** Posts ORDER_ACCEPTED for the order inside the transaction, as the application does. */
    private void post(Connection c, UUID order, long total) throws SQLException {
        ChartOfAccounts.Accounts accounts = ChartOfAccounts.ensure(c, w.tenant);
        new dev.chatledger.ledger.jdbc.JdbcLedgerRepository(new TenantScope(MatchWorld.DB.appDataSource()))
                .appendWithin(c, Postings.orderAccepted(w.tenant, order, total, accounts, Instant.now()));
    }

    // ── The total changes only when the order leaves NEEDS_REVIEW ──────────

    @Test
    void the_total_of_an_order_cannot_be_changed_in_any_status_outside_the_one_move() throws SQLException {
        for (String status : new String[]{"PENDING_PAYMENT", "PARTIALLY_PAID", "PAID", "NEEDS_REVIEW", "CANCELLED"}) {
            UUID order = w.order(5000, "PENDING_PAYMENT", null, null);
            if (!status.equals("PENDING_PAYMENT")) {
                MatchWorld.forced(c -> sql(c, "UPDATE orders SET status = ? WHERE id = ?", status, order));
            }
            assertThatThrownBy(() -> asApp(c -> sql(c, "UPDATE orders SET total_minor = 6000 WHERE id = ?", order)))
                    .as("as the application, status " + status).hasMessageContaining("can only be set when it leaves NEEDS_REVIEW");
            assertThatThrownBy(() -> asOwner(c -> sql(c, "UPDATE orders SET total_minor = 6000 WHERE id = ?", order)))
                    .as("as the schema owner, status " + status).hasMessageContaining("can only be set when it leaves NEEDS_REVIEW");
            assertThat(stringOf("SELECT total_minor::text FROM orders WHERE id = ?", order)).isEqualTo("5000");
        }
    }

    @Test
    void the_total_cannot_be_changed_while_the_order_stays_in_review_or_while_it_moves_anywhere_else() throws SQLException {
        UUID review = w.order(5000, "NEEDS_REVIEW", null, null);
        assertThatThrownBy(() -> asApp(c -> sql(c, "UPDATE orders SET total_minor = 6000 WHERE id = ?", review)))
                .hasMessageContaining("can only be set when it leaves NEEDS_REVIEW");
        for (String target : new String[]{"PARTIALLY_PAID", "PAID", "UNPARSED", "CANCELLED"}) {
            assertThatThrownBy(() -> asApp(c -> sql(c, "UPDATE orders SET total_minor = 6000, status = ? WHERE id = ?", target, review)))
                    .as("to " + target).isInstanceOf(SQLException.class);
        }
        assertThat(stringOf("SELECT status || ' ' || total_minor FROM orders WHERE id = ?", review)).isEqualTo("NEEDS_REVIEW 5000");
    }

    @Test
    void an_unchanged_total_in_an_update_is_not_a_change() throws SQLException {
        UUID order = w.order(5000, "PENDING_PAYMENT", null, null);
        asApp(c -> sql(c, "UPDATE orders SET total_minor = 5000 WHERE id = ?", order));
        assertThat(stringOf("SELECT total_minor::text FROM orders WHERE id = ?", order)).isEqualTo("5000");
    }

    @Test
    void the_move_with_a_new_total_and_its_posting_is_accepted_and_stored() throws SQLException {
        UUID order = w.order(5000, "NEEDS_REVIEW", "R-1", null);
        asApp(c -> {
            sql(c, "UPDATE orders SET total_minor = 7000, status = 'PENDING_PAYMENT' WHERE id = ?", order);
            post(c, order, 7000);
        });
        assertThat(stringOf("SELECT status || ' ' || total_minor FROM orders WHERE id = ?", order)).isEqualTo("PENDING_PAYMENT 7000");
    }

    @Test
    void an_order_that_had_no_total_gets_one_in_the_move() throws SQLException {
        UUID order = w.orderWithoutTotal("NEEDS_REVIEW");
        asApp(c -> {
            sql(c, "UPDATE orders SET total_minor = 4200, status = 'PENDING_PAYMENT' WHERE id = ?", order);
            post(c, order, 4200);
        });
        assertThat(stringOf("SELECT status || ' ' || total_minor FROM orders WHERE id = ?", order)).isEqualTo("PENDING_PAYMENT 4200");
    }

    // ── The posting is required at commit ──────────────────────────────────

    @Test
    void an_order_cannot_become_payable_without_its_posting() throws SQLException {
        UUID order = w.order(5000, "NEEDS_REVIEW", null, null);
        assertThatThrownBy(() -> asApp(c -> sql(c, "UPDATE orders SET status = 'PENDING_PAYMENT' WHERE id = ?", order)))
                .hasMessageContaining("became payable without its ORDER_ACCEPTED posting");
        assertThatThrownBy(() -> asOwner(c -> sql(c, "UPDATE orders SET status = 'PENDING_PAYMENT', total_minor = 9 WHERE id = ?", order)))
                .hasMessageContaining("became payable without its ORDER_ACCEPTED posting");
        assertThat(stringOf("SELECT status FROM orders WHERE id = ?", order)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void another_orders_posting_does_not_count() throws SQLException {
        UUID order = w.order(5000, "NEEDS_REVIEW", null, null);
        UUID other = w.order(5000, "PENDING_PAYMENT", null, null);
        assertThatThrownBy(() -> asApp(c -> {
            sql(c, "UPDATE orders SET status = 'PENDING_PAYMENT' WHERE id = ?", order);
            post(c, other, 5000);
        })).hasMessageContaining("became payable without its ORDER_ACCEPTED posting");
    }

    /** The other tenant's posting is balanced and has the same reference id; only this tenant's posting counts. Written as the schema owner. */
    @Test
    void a_posting_of_another_tenant_with_the_same_reference_does_not_count() throws SQLException {
        UUID order = w.order(5000, "NEEDS_REVIEW", null, null);
        MatchWorld other = new MatchWorld();
        assertThatThrownBy(() -> asOwner(c -> {
            ChartOfAccounts.Accounts theirs = ChartOfAccounts.ensure(c, other.tenant);
            UUID txn = UUID.randomUUID();
            sql(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'ORDER_ACCEPTED', 'ORDER', ?)", txn, other.tenant, order);
            sql(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, 5000, 0)", UUID.randomUUID(), txn, theirs.receivable());
            sql(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, 0, 5000)", UUID.randomUUID(), txn, theirs.revenue());
            sql(c, "UPDATE orders SET status = 'PENDING_PAYMENT' WHERE id = ?", order);
        })).hasMessageContaining("became payable without its ORDER_ACCEPTED posting");
        assertThat(stringOf("SELECT status FROM orders WHERE id = ?", order)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void a_posting_of_another_kind_does_not_count() throws SQLException {
        UUID order = w.order(5000, "NEEDS_REVIEW", null, null);
        assertThatThrownBy(() -> asApp(c -> {
            sql(c, "UPDATE orders SET status = 'PENDING_PAYMENT' WHERE id = ?", order);
            ChartOfAccounts.Accounts accounts = ChartOfAccounts.ensure(c, w.tenant);
            new dev.chatledger.ledger.jdbc.JdbcLedgerRepository(new TenantScope(MatchWorld.DB.appDataSource())).appendWithin(c,
                    new dev.chatledger.ledger.LedgerTransaction(UUID.randomUUID(), w.tenant, "SOMETHING_ELSE", "ORDER", order, null, Instant.now(),
                            List.of(dev.chatledger.ledger.LedgerEntry.debit(accounts.receivable(), 5000), dev.chatledger.ledger.LedgerEntry.credit(accounts.revenue(), 5000))));
        })).hasMessageContaining("became payable without its ORDER_ACCEPTED posting");
    }

    @Test
    void the_other_moves_among_open_statuses_do_not_need_a_posting() throws SQLException {
        // the match flows move orders between PENDING_PAYMENT, PARTIALLY_PAID and PAID; only the move out of review needs the new check
        UUID order = w.order(5000, "PENDING_PAYMENT", null, null);
        MatchWorld.forced(c -> sql(c, "UPDATE orders SET status = 'PARTIALLY_PAID' WHERE id = ?", order));
        assertThat(stringOf("SELECT status FROM orders WHERE id = ?", order)).isEqualTo("PARTIALLY_PAID");
    }

    // ── The total is bounded by the database too ───────────────────────────

    @Test
    void the_total_in_the_move_must_be_between_one_and_the_largest_amount() throws SQLException {
        for (long bad : new long[]{0, -1, 1_000_000_000_001L, Long.MAX_VALUE}) {
            UUID order = w.order(5000, "NEEDS_REVIEW", null, null);
            assertThatThrownBy(() -> asApp(c -> {
                sql(c, "UPDATE orders SET total_minor = ?, status = 'PENDING_PAYMENT' WHERE id = ?", bad, order);
                post(c, order, 5000);
            })).as("total " + bad).isInstanceOf(SQLException.class);
            assertThat(stringOf("SELECT status FROM orders WHERE id = ?", order)).isEqualTo("NEEDS_REVIEW");
        }
        UUID largest = w.order(5000, "NEEDS_REVIEW", null, null);
        asApp(c -> {
            sql(c, "UPDATE orders SET total_minor = 1000000000000, status = 'PENDING_PAYMENT' WHERE id = ?", largest);
            post(c, largest, 1_000_000_000_000L);
        });
        UUID smallest = w.order(5000, "NEEDS_REVIEW", null, null);
        asApp(c -> {
            sql(c, "UPDATE orders SET total_minor = 1, status = 'PENDING_PAYMENT' WHERE id = ?", smallest);
            post(c, smallest, 1);
        });
    }

    @Test
    void an_order_with_no_total_cannot_become_payable_without_one() throws SQLException {
        UUID order = w.orderWithoutTotal("NEEDS_REVIEW");
        assertThatThrownBy(() -> asApp(c -> {
            sql(c, "UPDATE orders SET status = 'PENDING_PAYMENT' WHERE id = ?", order);
            post(c, order, 5000);
        })).isInstanceOf(SQLException.class);
    }

    // ── The status move itself ─────────────────────────────────────────────

    @Test
    void out_of_review_the_only_move_is_to_pending_payment_and_nothing_goes_into_review() throws SQLException {
        for (String target : new String[]{"PARTIALLY_PAID", "PAID", "UNPARSED", "CANCELLED"}) {
            UUID order = w.order(5000, "NEEDS_REVIEW", null, null);
            assertThatThrownBy(() -> asOwner(c -> sql(c, "UPDATE orders SET status = ? WHERE id = ?", target, order))).as("review to " + target)
                    .hasMessageContaining("is not allowed");
        }
        for (String from : new String[]{"PENDING_PAYMENT", "PARTIALLY_PAID", "PAID"}) {
            UUID order = w.order(5000, "PENDING_PAYMENT", null, null);
            if (!from.equals("PENDING_PAYMENT")) {
                MatchWorld.forced(c -> sql(c, "UPDATE orders SET status = ? WHERE id = ?", from, order));
            }
            assertThatThrownBy(() -> asOwner(c -> sql(c, "UPDATE orders SET status = 'NEEDS_REVIEW' WHERE id = ?", order))).as(from + " to review")
                    .hasMessageContaining("is not allowed");
        }
        for (String from : new String[]{"UNPARSED", "CANCELLED"}) {
            UUID order = from.equals("UNPARSED") ? w.orderWithoutTotal("UNPARSED") : w.order(5000, "PENDING_PAYMENT", null, null);
            if (from.equals("CANCELLED")) {
                MatchWorld.forced(c -> sql(c, "UPDATE orders SET status = 'CANCELLED' WHERE id = ?", order));
            }
            assertThatThrownBy(() -> asOwner(c -> sql(c, "UPDATE orders SET status = 'PENDING_PAYMENT' WHERE id = ?", order))).as(from + " to payable")
                    .hasMessageContaining("is not allowed");
        }
    }

    // ── Privileges ─────────────────────────────────────────────────────────

    @Test
    void the_application_may_update_the_total_and_the_status_of_an_order_and_no_other_column() throws SQLException {
        List<String> may = List.of("status", "total_minor");
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'orders' ORDER BY ordinal_position")) {
            try (ResultSet rs = ps.executeQuery()) {
                int seen = 0;
                while (rs.next()) {
                    String column = rs.getString(1);
                    seen++;
                    try (PreparedStatement p = c.prepareStatement("SELECT has_column_privilege('chatledger_app', 'orders', ?, 'UPDATE')")) {
                        p.setString(1, column);
                        try (ResultSet r = p.executeQuery()) {
                            r.next();
                            assertThat(r.getBoolean(1)).as("UPDATE on orders." + column).isEqualTo(may.contains(column));
                        }
                    }
                }
                assertThat(seen).isGreaterThan(10);
            }
        }
    }

    // ── The tenant's time zone ─────────────────────────────────────────────

    @Test
    void a_tenant_time_zone_is_an_iana_style_name_and_nothing_else() throws SQLException {
        assertThat(stringOf("SELECT timezone FROM tenant WHERE id = ?", w.tenant)).as("the default").isEqualTo("UTC");
        for (String good : new String[]{"UTC", "Africa/Douala", "America/Argentina/Buenos_Aires", "Etc/GMT+1", "Asia/Ho_Chi_Minh"}) {
            w.run("UPDATE tenant SET timezone = ? WHERE id = ?", good, w.tenant);
            assertThat(stringOf("SELECT timezone FROM tenant WHERE id = ?", w.tenant)).isEqualTo(good);
        }
        for (String bad : new String[]{"", " ", "Africa/Douala ", "a b", "../etc", "Europe/Par is", "x".repeat(65), "Africa//Douala", "A/B/C/D", "UTC;DROP", "Africa/Do\nuala"}) {
            assertThatThrownBy(() -> w.run("UPDATE tenant SET timezone = ? WHERE id = ?", bad, w.tenant)).as("[" + bad + "]").isInstanceOf(IllegalStateException.class);
        }
    }
}
