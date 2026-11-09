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
    void clean_books_with_a_real_match_pass_all_six_and_agree_with_the_oracle() {
        MatchWorld w = world();
        BooksCheck.Result r = check(w);
        assertThat(r.lines()).extracting(BooksCheck.Line::code).containsExactly(Code.values());
        assertThat(r.balanced()).isTrue();
        assertThat(MatchInvariants.check(w.tenant)).isEmpty();
        assertThat(r.lines()).extracting(l -> l.code() + "=" + l.left() + "/" + l.right()).containsExactly(
                "TRIAL_BALANCE=0/0", "RECEIVABLE=0/0", "SALES=5000/5000", "CUSTOMER_CREDIT=1000/1000", "CLEARING=6000/6000", "MATCH_POSTINGS=1/1");
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
        assertThat(r.lines().get(5).left()).isZero();
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
                "TRIAL_BALANCE=0/0", "RECEIVABLE=0/0", "SALES=5000/5000", "CUSTOMER_CREDIT=1000/1000", "CLEARING=6000/6000", "MATCH_POSTINGS=1/1");
    }
}
