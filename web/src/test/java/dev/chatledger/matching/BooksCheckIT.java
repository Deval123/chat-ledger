package dev.chatledger.matching;

import dev.chatledger.board.BooksCheck;
import dev.chatledger.board.BooksCheck.Code;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each of the six comparisons of the books check, broken on its own: a forged posting written behind the application's back (as the schema
 * owner, or with the triggers off) must turn exactly the comparison it breaks red. On clean books with real matches the check agrees with the
 * independent oracle in {@link MatchInvariants}.
 *
 * <p>Requirements: 4.4, 9.1. Task 8.
 */
class BooksCheckIT {

    /** An order of 5,000 matched by a payment of 6,000 (1,000 excess), through the real service, so all six accounts have movements. */
    private static MatchWorld world() {
        MatchingFixture f = new MatchingFixture();
        MatchWorld w = new MatchWorld();
        UUID order = w.order(5000, "PENDING_PAYMENT", "BK-1", null);
        UUID payment = w.payment(6000, "RECEIVED");
        try (Connection c = w.owner.getConnection()) {
            c.setAutoCommit(false);
            ChartOfAccounts.Accounts a = ChartOfAccounts.ensure(c, w.tenant);
            UUID txn = UUID.randomUUID();
            MatchWorld.run(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'ORDER_ACCEPTED', 'ORDER', ?)", txn, w.tenant, order);
            MatchWorld.run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, 5000, 0)", UUID.randomUUID(), txn, a.receivable());
            MatchWorld.run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, 0, 5000)", UUID.randomUUID(), txn, a.revenue());
            c.commit();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(f.matcher.matchManually(w.tenant, order, payment, w.user).outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        return w;
    }

    private static BooksCheck.Result check(MatchWorld w) {
        return new BooksCheck(MatchWorld.DB.appDataSource(), Clock.systemUTC()).check(w.tenant, true);
    }

    private static Code[] failing(BooksCheck.Result r) {
        return r.lines().stream().filter(l -> !l.ok()).map(BooksCheck.Line::code).toArray(Code[]::new);
    }

    /** A balanced forged transaction of 100 between two accounts (debit first), written by the schema owner. */
    private static void forge(MatchWorld w, String kind, String debitCode, String creditCode) {
        try (Connection c = w.owner.getConnection()) {
            c.setAutoCommit(false);
            ChartOfAccounts.ensure(c, w.tenant);
            UUID txn = UUID.randomUUID();
            MatchWorld.run(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, ?, 'MATCH', ?)", txn, w.tenant, kind, UUID.randomUUID());
            MatchWorld.run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) "
                    + "SELECT gen_random_uuid(), ?, id, 100, 0 FROM account WHERE tenant_id = ? AND code = ?", txn, w.tenant, debitCode);
            MatchWorld.run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) "
                    + "SELECT gen_random_uuid(), ?, id, 0, 100 FROM account WHERE tenant_id = ? AND code = ?", txn, w.tenant, creditCode);
            c.commit();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void clean_books_with_a_real_match_pass_every_line_and_agree_with_the_oracle() {
        MatchWorld w = world();
        BooksCheck.Result r = check(w);
        assertThat(r.lines()).extracting(BooksCheck.Line::code).containsExactly(Code.values());
        assertThat(r.balanced()).isTrue();
        assertThat(MatchInvariants.check(w.tenant)).isEmpty();
        assertThat(r.lines()).extracting(l -> l.code() + "=" + l.left() + "/" + l.right()).containsExactly(
                "TRIAL_BALANCE=0/0", "RECEIVABLE=0/0", "SALES=5000/5000", "CUSTOMER_CREDIT=1000/1000", "CLEARING=6000/6000", "MATCH_POSTINGS=1/1",
                "ACCEPTANCE_POSTINGS=1/1", "CANCELLED_CLEAR=0/0");
    }

    @Test
    void a_one_sided_entry_breaks_the_trial_balance() {
        MatchWorld w = world();
        MatchWorld.forced(c -> {
            UUID txn = UUID.randomUUID();
            MatchWorld.run(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'ADJUSTMENT', 'NONE', ?)", txn, w.tenant, UUID.randomUUID());
            MatchWorld.run(c, "INSERT INTO account(id, tenant_id, code, name, type) VALUES (?, ?, '9999', 'Forged', 'EXPENSE')", UUID.randomUUID(), w.tenant);
            MatchWorld.run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) "
                    + "SELECT gen_random_uuid(), ?, id, 100, 0 FROM account WHERE tenant_id = ? AND code = '9999'", txn, w.tenant);
        });
        assertThat(failing(check(w))).containsExactly(Code.TRIAL_BALANCE);
    }

    @Test
    void money_moved_from_clearing_to_receivable_breaks_receivable_and_clearing() {
        MatchWorld w = world();
        forge(w, "ADJUSTMENT", "1100", "1000");
        assertThat(failing(check(w))).containsExactly(Code.RECEIVABLE, Code.CLEARING);
    }

    @Test
    void revenue_without_an_accepted_order_breaks_sales_and_receivable() {
        MatchWorld w = world();
        forge(w, "ADJUSTMENT", "1100", "4000");
        assertThat(failing(check(w))).containsExactly(Code.RECEIVABLE, Code.SALES);
    }

    @Test
    void credit_without_a_match_breaks_customer_credit_and_clearing() {
        MatchWorld w = world();
        forge(w, "ADJUSTMENT", "1000", "2100");
        assertThat(failing(check(w))).containsExactly(Code.CUSTOMER_CREDIT, Code.CLEARING);
    }

    @Test
    void a_payment_posting_without_a_match_breaks_the_posting_count_and_nothing_else_when_it_nets_to_zero() {
        MatchWorld w = world();
        forge(w, "PAYMENT_MATCHED", "1000", "1000");
        assertThat(failing(check(w))).containsExactly(Code.MATCH_POSTINGS);
    }

    @Test
    void a_reversed_posting_no_longer_counts() {
        MatchWorld w = world();
        UUID match = UUID.fromString(MatchingFixture.rows("SELECT id::text FROM match WHERE tenant_id = ? AND state = 'CONFIRMED'", w.tenant).get(0));
        assertThat(new MatchingFixture().matcher.unmatch(w.tenant, match, w.user).done()).isTrue();
        BooksCheck.Result r = check(w);
        assertThat(r.balanced()).isTrue();
        assertThat(r.lines().stream().filter(l -> l.code() == Code.MATCH_POSTINGS).findFirst().orElseThrow().left()).isZero();
        assertThat(MatchInvariants.check(w.tenant)).isEmpty();
    }

    /** Run as the schema owner, which row-level security does not bind: another tenant's broken books must not leak into this tenant's check. */
    @Test
    void the_check_scopes_every_sum_by_tenant_by_itself() {
        MatchWorld clean = world();
        MatchWorld broken = world();
        forge(broken, "ADJUSTMENT", "1100", "4000");
        forge(broken, "ADJUSTMENT", "1000", "2100");
        forge(broken, "PAYMENT_MATCHED", "1000", "1000");
        MatchWorld.forced(c -> {
            UUID txn = UUID.randomUUID();
            MatchWorld.run(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'ADJUSTMENT', 'NONE', ?)", txn, broken.tenant, UUID.randomUUID());
            MatchWorld.run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) "
                    + "SELECT gen_random_uuid(), ?, id, 100, 0 FROM account WHERE tenant_id = ? AND code = '1100'", txn, broken.tenant);
        });
        BooksCheck asOwner = new BooksCheck(MatchWorld.DB.migrateDataSource(), Clock.systemUTC());
        assertThat(asOwner.check(broken.tenant, true).balanced()).as("control: the broken tenant is broken").isFalse();
        BooksCheck.Result r = asOwner.check(clean.tenant, true);
        assertThat(r.lines()).extracting(l -> l.code() + "=" + l.left() + "/" + l.right()).containsExactly(
                "TRIAL_BALANCE=0/0", "RECEIVABLE=0/0", "SALES=5000/5000", "CUSTOMER_CREDIT=1000/1000", "CLEARING=6000/6000", "MATCH_POSTINGS=1/1",
                "ACCEPTANCE_POSTINGS=1/1", "CANCELLED_CLEAR=0/0");
    }

    // ── Task 12b: the acceptance postings ───────────────────────────────────

    private static UUID orderOf(MatchWorld w) {
        return UUID.fromString(MatchingFixture.rows("SELECT order_id::text FROM match WHERE tenant_id = ? AND state = 'CONFIRMED'", w.tenant).get(0));
    }

    /**
     * The owner's condition 3: an order's acceptance posting is removed behind the application's back. RECEIVABLE and SALES cannot see it
     * (the order leaves "accepted" and the ledger together); ACCEPTANCE_POSTINGS is the line that does.
     */
    @Test
    void an_acceptance_posting_removed_from_a_payable_order_breaks_acceptance_postings_and_nothing_else() {
        MatchWorld w = world();
        UUID order = orderOf(w);
        MatchWorld.forced(c -> {
            MatchWorld.run(c, "DELETE FROM ledger_entry WHERE txn_id IN (SELECT id FROM ledger_txn WHERE tenant_id = ? AND kind = 'ORDER_ACCEPTED' AND ref_id = ?)", w.tenant, order);
            MatchWorld.run(c, "DELETE FROM ledger_txn WHERE tenant_id = ? AND kind = 'ORDER_ACCEPTED' AND ref_id = ?", w.tenant, order);
        });
        assertThat(failing(check(w))).containsExactly(Code.ACCEPTANCE_POSTINGS);
    }

    /** The same, by a reversal of the acceptance of an order that stays payable (the database refuses it at commit: V021, so forced). */
    @Test
    void an_acceptance_reversed_on_a_payable_order_breaks_acceptance_postings_and_nothing_else() {
        MatchWorld w = world();
        UUID order = orderOf(w);
        MatchWorld.forced(c -> reverseAcceptance(c, w.tenant, order));
        assertThat(failing(check(w))).containsExactly(Code.ACCEPTANCE_POSTINGS);
    }

    @Test
    void a_cancelled_order_that_keeps_its_posting_breaks_cancelled_clear_and_the_count() {
        MatchWorld w = new MatchWorld();
        UUID order = postedOrder(w, 4000);
        MatchWorld.forced(c -> MatchWorld.run(c, "UPDATE orders SET status = 'CANCELLED' WHERE id = ?", order));
        assertThat(failing(check(w))).containsExactly(Code.ACCEPTANCE_POSTINGS, Code.CANCELLED_CLEAR);
    }

    /** One fault hides the other in the count; CANCELLED_CLEAR still sees it, so neither line stands in for the other. */
    @Test
    void a_cancelled_order_with_its_posting_and_a_payable_one_without_breaks_cancelled_clear_alone() {
        MatchWorld w = new MatchWorld();
        UUID kept = postedOrder(w, 4000);
        w.order(4000, "PENDING_PAYMENT", null, null);   // payable, never posted
        MatchWorld.forced(c -> MatchWorld.run(c, "UPDATE orders SET status = 'CANCELLED' WHERE id = ?", kept));
        assertThat(failing(check(w))).containsExactly(Code.CANCELLED_CLEAR);
    }

    @Test
    void a_real_cancel_leaves_every_line_balanced() {
        MatchWorld w = new MatchWorld();
        MatchingFixture f = new MatchingFixture();
        UUID order = postedOrder(w, 4000);
        assertThat(new dev.chatledger.orders.RecordActionsService(MatchingFixture.POOL, f.repository, f.orderRepository, f.clock)
                .cancel(w.tenant, order, w.user)).isEqualTo(dev.chatledger.orders.RecordActionsService.Outcome.DONE);
        BooksCheck.Result r = check(w);
        assertThat(r.balanced()).as(String.valueOf(r.lines())).isTrue();
        assertThat(r.lines()).extracting(l -> l.code() + "=" + l.left() + "/" + l.right())
                .contains("RECEIVABLE=0/0", "SALES=0/0", "ACCEPTANCE_POSTINGS=0/0", "CANCELLED_CLEAR=0/0");
        assertThat(MatchInvariants.check(w.tenant)).isEmpty();
    }

    /** A payable order with its acceptance posting, as ingestion writes it. */
    static UUID postedOrder(MatchWorld w, long total) {
        UUID order = w.order(total, "PENDING_PAYMENT", null, null);
        try (Connection c = w.owner.getConnection()) {
            c.setAutoCommit(false);
            ChartOfAccounts.Accounts a = ChartOfAccounts.ensure(c, w.tenant);
            UUID txn = UUID.randomUUID();
            MatchWorld.run(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'ORDER_ACCEPTED', 'ORDER', ?)", txn, w.tenant, order);
            MatchWorld.run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, ?, 0)", UUID.randomUUID(), txn, a.receivable(), total);
            MatchWorld.run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, 0, ?)", UUID.randomUUID(), txn, a.revenue(), total);
            c.commit();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return order;
    }

    /** Mirrors an order's ORDER_ACCEPTED posting as a REVERSAL, on the given connection. */
    static void reverseAcceptance(Connection c, UUID tenant, UUID order) throws java.sql.SQLException {
        UUID reversal = UUID.randomUUID();
        MatchWorld.run(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id, reverses_txn_id) "
                + "SELECT ?, tenant_id, 'REVERSAL', 'LEDGER_TXN', id, id FROM ledger_txn WHERE tenant_id = ? AND kind = 'ORDER_ACCEPTED' AND ref_id = ?", reversal, tenant, order);
        MatchWorld.run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) "
                + "SELECT gen_random_uuid(), ?, e.account_id, e.credit_minor, e.debit_minor FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id "
                + "WHERE t.tenant_id = ? AND t.kind = 'ORDER_ACCEPTED' AND t.ref_id = ?", reversal, tenant, order);
    }
}
