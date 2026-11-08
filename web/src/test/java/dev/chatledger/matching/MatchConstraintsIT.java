package dev.chatledger.matching;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the database itself refuses (V016), whoever sends the rows: every invariant of a match, the consistency of statuses at commit,
 * the allowed status moves, and the exact privileges of the application role. The rows are written as the schema owner so that a refusal is
 * a constraint or a trigger and not a privilege.
 *
 * <p>Requirements: 3, 4. Task 6.1.
 */
class MatchConstraintsIT {

    @FunctionalInterface
    private interface Work {
        void run(Connection c) throws SQLException;
    }

    private static void tx(MatchWorld w, Work work) throws SQLException {
        try (Connection c = w.owner.getConnection()) {
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

    /** Runs the work and rolls back: for statements whose own refusal or acceptance is the question. */
    private static void tryOnly(MatchWorld w, Work work) throws SQLException {
        try (Connection c = w.owner.getConnection()) {
            c.setAutoCommit(false);
            try {
                work.run(c);
            } finally {
                c.rollback();
            }
        }
    }

    /** The statement is refused with this SQL state and a message containing the text (looking through any wrapper). */
    private static void refuses(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String state, String contains) {
        assertThatThrownBy(call).satisfies(thrown -> {
            Throwable root = thrown;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(SQLException.class);
            assertThat(((SQLException) root).getSQLState()).isEqualTo(state);
            assertThat(root.getMessage()).contains(contains);
        });
    }

    /** A posting and a match in one transaction; {@code ref} false puts a different id on the posting. */
    private static void match(MatchWorld w, UUID order, UUID payment, String state, long amount, long excess, long debit, long receivable,
                              long credit, String kind, boolean ref) throws SQLException {
        tx(w, c -> {
            UUID id = UUID.randomUUID();
            ChartOfAccounts.Accounts a = ChartOfAccounts.ensure(c, w.tenant);
            UUID txn = w.posting(c, a, ref ? id : UUID.randomUUID(), kind, debit, receivable, credit);
            w.match(c, id, order, payment, state, amount, excess, txn, "AUTO".equals(state) ? null : w.user, !"AUTO".equals(state), "[]");
        });
    }

    /** A valid exact match of 5000 on a fresh PAID order and MATCHED payment, except for what the caller changes. */
    private static void exactMatch(MatchWorld w, String orderStatus, String paymentStatus, boolean paymentTx, String state) throws SQLException {
        UUID order = w.order(5000, orderStatus, null, null);
        UUID payment = w.payment(5000, paymentStatus, paymentTx);
        match(w, order, payment, state, 5000, 0, 5000, 5000, 0, "PAYMENT_MATCHED", true);
    }

    // ── A complete match commits ───────────────────────────────────────────

    @Test
    void an_exact_a_partial_and_an_over_payment_each_commit_with_their_postings() {
        MatchWorld w = new MatchWorld();
        UUID exactOrder = w.order(5000, "PAID", "A-1", null);
        UUID exactPayment = w.payment(5000, "MATCHED");
        assertThatCode(() -> w.activeMatch(exactOrder, exactPayment, "AUTO", 5000, 0)).doesNotThrowAnyException();

        UUID partialOrder = w.order(5000, "PARTIALLY_PAID", "A-2", null);
        UUID partialPayment = w.payment(2000, "MATCHED");
        assertThatCode(() -> w.activeMatch(partialOrder, partialPayment, "CONFIRMED", 2000, 0)).doesNotThrowAnyException();

        UUID overOrder = w.order(5000, "PAID", "A-3", null);
        UUID overPayment = w.payment(8000, "MATCHED");
        assertThatCode(() -> w.activeMatch(overOrder, overPayment, "CONFIRMED", 5000, 3000)).doesNotThrowAnyException();
    }

    @Test
    void several_payments_may_pay_one_order_up_to_its_total() throws SQLException {
        MatchWorld w = new MatchWorld();
        UUID order = w.order(5000, "PAID", null, null);
        UUID first = w.payment(2000, "MATCHED");
        UUID second = w.payment(4000, "MATCHED");
        tx(w, c -> {
            ChartOfAccounts.Accounts a = ChartOfAccounts.ensure(c, w.tenant);
            UUID m1 = UUID.randomUUID();
            UUID m2 = UUID.randomUUID();
            w.match(c, m1, order, first, "CONFIRMED", 2000L, 0, w.posting(c, a, m1, "PAYMENT_MATCHED", 2000, 2000, 0), w.user, true, "[]");
            w.match(c, m2, order, second, "CONFIRMED", 3000L, 1000, w.posting(c, a, m2, "PAYMENT_MATCHED", 4000, 3000, 1000), w.user, true, "[]");
        });
    }

    // ── What is refused at commit ──────────────────────────────────────────

    @Test
    void the_active_matches_of_an_order_can_never_exceed_its_total() {
        MatchWorld w = new MatchWorld();
        UUID order = w.order(5000, "PAID", null, null);
        UUID first = w.payment(3000, "MATCHED");
        UUID second = w.payment(3000, "MATCHED");
        refuses(() -> tx(w, c -> {
            ChartOfAccounts.Accounts a = ChartOfAccounts.ensure(c, w.tenant);
            UUID m1 = UUID.randomUUID();
            UUID m2 = UUID.randomUUID();
            w.match(c, m1, order, first, "CONFIRMED", 3000L, 0, w.posting(c, a, m1, "PAYMENT_MATCHED", 3000, 3000, 0), w.user, true, "[]");
            w.match(c, m2, order, second, "CONFIRMED", 3000L, 0, w.posting(c, a, m2, "PAYMENT_MATCHED", 3000, 3000, 0), w.user, true, "[]");
        }), "23514", "exceed its total");
    }

    @Test
    void amount_plus_excess_must_be_the_payment_amount() {
        MatchWorld w = new MatchWorld();
        UUID partly = w.order(5000, "PARTIALLY_PAID", null, null);
        UUID payment = w.payment(5000, "MATCHED");
        refuses(() -> match(w, partly, payment, "AUTO", 4000, 0, 4000, 4000, 0, "PAYMENT_MATCHED", true), "23514", "amount plus excess");
        UUID paid = w.order(5000, "PAID", null, null);
        refuses(() -> match(w, paid, payment, "CONFIRMED", 5000, 1, 5001, 5000, 1, "PAYMENT_MATCHED", true), "23514", "amount plus excess");
        refuses(() -> match(w, paid, payment, "CONFIRMED", 4999, 0, 4999, 4999, 0, "PAYMENT_MATCHED", true), "23514", "but its matches make it PARTIALLY_PAID");
    }

    @Test
    void the_order_status_must_be_what_its_matches_make_it() {
        MatchWorld w = new MatchWorld();
        refuses(() -> exactMatch(w, "PENDING_PAYMENT", "MATCHED", true, "AUTO"), "23514", "but its matches make it PAID");
        refuses(() -> exactMatch(w, "PARTIALLY_PAID", "MATCHED", true, "AUTO"), "23514", "but its matches make it PAID");
        for (String status : new String[]{"NEEDS_REVIEW", "CANCELLED"}) {
            refuses(() -> exactMatch(w, status, "MATCHED", true, "AUTO"), "23514", "but its matches make it PAID");
        }
        UUID partial = w.order(5000, "PAID", null, null);
        UUID payment = w.payment(2000, "MATCHED");
        refuses(() -> match(w, partial, payment, "CONFIRMED", 2000, 0, 2000, 2000, 0, "PAYMENT_MATCHED", true), "23514", "but its matches make it PARTIALLY_PAID");
    }

    @Test
    void an_order_without_a_total_can_never_be_matched() {
        MatchWorld w = new MatchWorld();
        for (String status : new String[]{"NEEDS_REVIEW", "UNPARSED"}) {
            UUID order = w.orderWithoutTotal(status);
            UUID payment = w.payment(5000, "MATCHED");
            refuses(() -> match(w, order, payment, "CONFIRMED", 5000, 0, 5000, 5000, 0, "PAYMENT_MATCHED", true), "23514", "has no total");
        }
    }

    @Test
    void a_payment_is_matched_exactly_when_it_has_an_active_match() {
        MatchWorld w = new MatchWorld();
        refuses(() -> exactMatch(w, "PAID", "RECEIVED", true, "AUTO"), "23514", "the payment status is RECEIVED");
        refuses(() -> exactMatch(w, "PAID", "UNVERIFIED", false, "CONFIRMED"), "23514", "the payment status is UNVERIFIED");
        refuses(() -> exactMatch(w, "PAID", "UNMATCHED", true, "CONFIRMED"), "23514", "the payment status is UNMATCHED");
    }

    @Test
    void a_duplicate_or_unparsed_payment_can_never_have_a_match() throws SQLException {
        MatchWorld w = new MatchWorld();
        UUID original = w.payment(5000, "RECEIVED");
        UUID duplicate = w.duplicateOf(original, 5000);
        UUID order = w.order(5000, "PAID", null, null);
        refuses(() -> match(w, order, duplicate, "CONFIRMED", 5000, 0, 5000, 5000, 0, "PAYMENT_MATCHED", true), "23514", "the payment status is DUPLICATE");
        UUID unparsed = w.unparsedPayment();
        refuses(() -> match(w, order, unparsed, "CONFIRMED", 5000, 0, 5000, 5000, 0, "PAYMENT_MATCHED", true), "23514", "the payment status is UNPARSED");
        // and the status cannot be moved to MATCHED by hand either
        refuses(() -> tryOnly(w, c -> MatchWorld.run(c, "UPDATE payment SET status = 'MATCHED' WHERE id = ?", duplicate)), "23514", "is not allowed");
        refuses(() -> tryOnly(w, c -> MatchWorld.run(c, "UPDATE payment SET status = 'MATCHED' WHERE id = ?", unparsed)), "23514", "is not allowed");
    }

    @Test
    void an_automatic_match_needs_a_transaction_id_but_a_confirmed_one_does_not() {
        MatchWorld w = new MatchWorld();
        refuses(() -> exactMatch(w, "PAID", "MATCHED", false, "AUTO"), "23514", "needs a provider transaction id");
        assertThatCode(() -> exactMatch(w, "PAID", "MATCHED", false, "CONFIRMED")).doesNotThrowAnyException();
        assertThatCode(() -> exactMatch(w, "PAID", "MATCHED", true, "AUTO")).doesNotThrowAnyException();
    }

    @Test
    void payment_and_order_must_share_the_currency() {
        MatchWorld w = new MatchWorld();
        UUID order = w.order(5000, "PAID", null, null, "EUR", java.time.Instant.now());
        UUID payment = w.payment(5000, "MATCHED");
        refuses(() -> match(w, order, payment, "CONFIRMED", 5000, 0, 5000, 5000, 0, "PAYMENT_MATCHED", true), "23514", "different currencies");
    }

    @Test
    void the_posting_must_have_exactly_the_amounts_of_the_match_on_the_right_accounts() {
        MatchWorld w = new MatchWorld();
        UUID order = w.order(5000, "PAID", null, null);
        UUID payment = w.payment(5000, "MATCHED");
        String message = "ledger posting does not have its amounts";
        // too little, too much, the credit on the wrong account, the excess on the wrong account, another kind, another reference
        refuses(() -> match(w, order, payment, "CONFIRMED", 5000, 0, 4000, 4000, 0, "PAYMENT_MATCHED", true), "23514", message);
        refuses(() -> match(w, order, payment, "CONFIRMED", 5000, 0, 6000, 6000, 0, "PAYMENT_MATCHED", true), "23514", message);
        refuses(() -> match(w, order, payment, "CONFIRMED", 5000, 0, 5000, 0, 5000, "PAYMENT_MATCHED", true), "23514", message);
        refuses(() -> match(w, order, payment, "CONFIRMED", 5000, 0, 5000, 4000, 1000, "PAYMENT_MATCHED", true), "23514", message);
        refuses(() -> match(w, order, payment, "CONFIRMED", 5000, 0, 5000, 5000, 0, "ORDER_ACCEPTED", true), "23514", message);
        refuses(() -> match(w, order, payment, "CONFIRMED", 5000, 0, 5000, 5000, 0, "PAYMENT_MATCHED", false), "23514", message);
        UUID overOrder = w.order(5000, "PAID", null, null);
        UUID overPayment = w.payment(8000, "MATCHED");
        refuses(() -> match(w, overOrder, overPayment, "CONFIRMED", 5000, 3000, 8000, 8000, 0, "PAYMENT_MATCHED", true), "23514", message);
        assertThatCode(() -> match(w, overOrder, overPayment, "CONFIRMED", 5000, 3000, 8000, 5000, 3000, "PAYMENT_MATCHED", true)).doesNotThrowAnyException();
    }

    /** A posting that debits and credits the accounts the caller names, instead of the standard ones. */
    private static void oddPosting(MatchWorld w, UUID order, UUID payment, long amount, long excess, java.util.function.Function<ChartOfAccounts.Accounts, UUID> debit,
                                   java.util.function.Function<ChartOfAccounts.Accounts, UUID> creditForAmount,
                                   java.util.function.Function<ChartOfAccounts.Accounts, UUID> creditForExcess) throws SQLException {
        tx(w, c -> {
            UUID id = UUID.randomUUID();
            ChartOfAccounts.Accounts a = ChartOfAccounts.ensure(c, w.tenant);
            UUID txn = UUID.randomUUID();
            MatchWorld.run(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, 'PAYMENT_MATCHED', 'MATCH', ?)", txn, w.tenant, id);
            MatchWorld.run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, ?, 0)",
                    UUID.randomUUID(), txn, debit.apply(a), amount + excess);
            MatchWorld.run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, 0, ?)",
                    UUID.randomUUID(), txn, creditForAmount.apply(a), amount);
            if (excess > 0) {
                MatchWorld.run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, 0, ?)",
                        UUID.randomUUID(), txn, creditForExcess.apply(a), excess);
            }
            w.match(c, id, order, payment, "CONFIRMED", amount, excess, txn, w.user, true, "[]");
        });
    }

    @Test
    void each_side_of_the_posting_must_be_on_its_own_account() {
        MatchWorld w = new MatchWorld();
        String message = "ledger posting does not have its amounts";
        UUID order = w.order(5000, "PAID", null, null);
        UUID payment = w.payment(8000, "MATCHED");
        // the standard posting, as a control
        assertThatCode(() -> oddPosting(w, order, payment, 5000, 3000, ChartOfAccounts.Accounts::clearing, ChartOfAccounts.Accounts::receivable,
                ChartOfAccounts.Accounts::customerCredit)).doesNotThrowAnyException();
        UUID order2 = w.order(5000, "PAID", null, null);
        UUID payment2 = w.payment(8000, "MATCHED");
        // the debit on revenue instead of the clearing account
        refuses(() -> oddPosting(w, order2, payment2, 5000, 3000, ChartOfAccounts.Accounts::revenue, ChartOfAccounts.Accounts::receivable,
                ChartOfAccounts.Accounts::customerCredit), "23514", message);
        // the amount credited to revenue instead of the receivable
        refuses(() -> oddPosting(w, order2, payment2, 5000, 3000, ChartOfAccounts.Accounts::clearing, ChartOfAccounts.Accounts::revenue,
                ChartOfAccounts.Accounts::customerCredit), "23514", message);
        // the excess credited to revenue instead of the customer's credit
        refuses(() -> oddPosting(w, order2, payment2, 5000, 3000, ChartOfAccounts.Accounts::clearing, ChartOfAccounts.Accounts::receivable,
                ChartOfAccounts.Accounts::revenue), "23514", message);
    }

    @Test
    void a_reversed_match_needs_the_reversal_of_its_posting() throws SQLException {
        MatchWorld w = new MatchWorld();
        UUID order = w.order(5000, "PENDING_PAYMENT", null, null);
        UUID payment = w.payment(5000, "RECEIVED");
        refuses(() -> tx(w, c -> {
            ChartOfAccounts.Accounts a = ChartOfAccounts.ensure(c, w.tenant);
            UUID id = UUID.randomUUID();
            UUID txn = w.posting(c, a, id, "PAYMENT_MATCHED", 5000, 5000, 0);
            w.match(c, id, order, payment, "AUTO", 5000L, 0, txn, null, false, "[]");
            MatchWorld.run(c, "UPDATE match SET state = 'REVERSED', decided_by = ?, decided_at = now() WHERE id = ?", w.user, id);
        }), "23514", "needs the reversal of its posting");
        UUID order2 = w.order(5000, "PENDING_PAYMENT", null, null);
        UUID payment2 = w.payment(5000, "RECEIVED");
        assertThatCode(() -> w.reversedMatch(order2, payment2, 5000)).doesNotThrowAnyException();
    }

    @Test
    void a_suggestion_does_not_depend_on_the_state_of_the_books() {
        MatchWorld w = new MatchWorld();
        // An order whose status was written by hand (say, imported) and has no matches: the commit-time checks are for active matches only.
        UUID order = w.order(5000, "PAID", null, null);
        UUID payment = w.payment(5000, "RECEIVED");
        assertThatCode(() -> w.suggested(order, payment)).doesNotThrowAnyException();
        assertThatCode(() -> w.rejected(order, payment, true)).doesNotThrowAnyException();
    }

    // ── Concurrency inside the commit-time check ───────────────────────────

    private static void matchOn(Connection c, MatchWorld w, ChartOfAccounts.Accounts a, UUID order, UUID payment) throws SQLException {
        UUID id = UUID.randomUUID();
        UUID txn = w.posting(c, a, id, "PAYMENT_MATCHED", 3000, 3000, 0);
        w.match(c, id, order, payment, "CONFIRMED", 3000L, 0, txn, w.user, true, "[]");
        MatchWorld.run(c, "SET CONSTRAINTS ALL IMMEDIATE");   // run the commit-time checks now; the locks they take last until the end
    }

    @Test
    void the_commit_time_check_locks_the_order_so_two_concurrent_matches_cannot_both_pass() throws Exception {
        MatchWorld w = new MatchWorld();
        UUID order = w.order(5000, "PARTIALLY_PAID", null, null);
        UUID first = w.payment(3000, "MATCHED");
        UUID second = w.payment(3000, "MATCHED");
        ChartOfAccounts.Accounts accounts;
        try (Connection c = w.owner.getConnection()) {
            accounts = ChartOfAccounts.ensure(c, w.tenant);   // committed first, so only the order row can make a transaction wait
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Connection c1 = w.owner.getConnection()) {
            c1.setAutoCommit(false);
            matchOn(c1, w, accounts, order, first);
            java.util.concurrent.Future<Object> other = pool.submit(() -> {
                try (Connection c2 = w.owner.getConnection()) {
                    c2.setAutoCommit(false);
                    matchOn(c2, w, accounts, order, second);
                    c2.commit();
                }
                return null;
            });
            Thread.sleep(1500);
            assertThat(other.isDone()).as("the second check waits for the first transaction").isFalse();
            c1.commit();
            assertThatThrownBy(() -> other.get(30, java.util.concurrent.TimeUnit.SECONDS)).hasRootCauseInstanceOf(SQLException.class)
                    .rootCause().hasMessageContaining("exceed its total");
        } finally {
            pool.shutdownNow();
        }
    }

    // ── Unique indexes ─────────────────────────────────────────────────────

    @Test
    void a_payment_has_at_most_one_active_match() {
        MatchWorld w = new MatchWorld();
        UUID payment = w.payment(5000, "MATCHED");
        UUID first = w.order(5000, "PAID", null, null);
        UUID second = w.order(5000, "PAID", null, null);
        w.activeMatch(first, payment, "AUTO", 5000, 0);
        refuses(() -> w.activeMatch(second, payment, "CONFIRMED", 5000, 0), "23505", "uq_match_active_payment");
    }

    @Test
    void a_pair_has_at_most_one_open_match_but_a_rejected_one_does_not_count() {
        MatchWorld w = new MatchWorld();
        UUID order = w.order(5000, "PENDING_PAYMENT", null, null);
        UUID payment = w.payment(5000, "RECEIVED");
        w.suggested(order, payment);
        refuses(() -> w.suggested(order, payment), "23505", "uq_match_open_pair");
        UUID order2 = w.order(5000, "PENDING_PAYMENT", null, null);
        w.rejected(order2, payment, true);
        w.rejected(order2, payment, false);
        assertThatCode(() -> w.suggested(order2, payment)).doesNotThrowAnyException();
    }

    /** A balanced transaction of the given kind and reference (two entries), the smallest legal ledger row. */
    private static void txn(MatchWorld w, String kind, UUID ref) throws SQLException {
        tx(w, c -> w.posting(c, ChartOfAccounts.ensure(c, w.tenant), ref, kind, 100, 100, 0));
    }

    @Test
    void there_is_one_posting_per_business_event_but_reversals_are_not_postings() throws SQLException {
        MatchWorld w = new MatchWorld();
        UUID ref = UUID.randomUUID();
        txn(w, "ORDER_ACCEPTED", ref);
        refuses(() -> txn(w, "ORDER_ACCEPTED", ref), "23505", "uq_ledger_txn_posting");
        // another kind on the same reference is another event, and so is the same reference in another tenant
        txn(w, "PAYMENT_MATCHED", ref);
        refuses(() -> txn(w, "PAYMENT_MATCHED", ref), "23505", "uq_ledger_txn_posting");
        MatchWorld other = new MatchWorld();
        txn(other, "ORDER_ACCEPTED", ref);
        // kinds that are not business postings are not limited
        txn(w, "SOMETHING_ELSE", ref);
        txn(w, "SOMETHING_ELSE", ref);
    }

    // ── Column checks ──────────────────────────────────────────────────────

    private static void insertMatch(MatchWorld w, String state, Long amount, long excess, boolean ledger, UUID decidedBy, boolean decidedAt, int score,
                                    String reasons) {
        UUID order = w.order(5000, "PENDING_PAYMENT", null, null);
        UUID payment = w.payment(5000, "RECEIVED");
        w.run("INSERT INTO match(id, tenant_id, order_id, payment_id, amount_minor, excess_minor, score, reasons_json, state, ledger_txn_id, decided_by, decided_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + (decidedAt ? "now()" : "NULL") + ")",
                UUID.randomUUID(), w.tenant, order, payment, amount, excess, score, reasons, state, ledger ? UUID.randomUUID() : null, decidedBy);
    }

    @Test
    void every_column_of_a_match_is_tied_to_its_state() {
        MatchWorld w = new MatchWorld();
        // a suggestion or a rejection applies nothing
        refuses(() -> insertMatch(w, "SUGGESTED", 5000L, 0, false, null, false, 90, "[]"), "23514", "chk_match_by_state");
        refuses(() -> insertMatch(w, "SUGGESTED", null, 1, false, null, false, 90, "[]"), "23514", "chk_match_by_state");
        refuses(() -> insertMatch(w, "SUGGESTED", null, 0, true, null, false, 90, "[]"), "23514", "chk_match_by_state");
        refuses(() -> insertMatch(w, "REJECTED", 5000L, 0, false, null, true, 90, "[]"), "23514", "chk_match_by_state");
        // an active or reversed match has its amounts in range and its posting
        refuses(() -> insertMatch(w, "AUTO", null, 0, true, null, false, 90, "[]"), "23514", "chk_match_by_state");
        refuses(() -> insertMatch(w, "AUTO", 5000L, 0, false, null, false, 90, "[]"), "23514", "chk_match_by_state");
        refuses(() -> insertMatch(w, "AUTO", 0L, 0, true, null, false, 90, "[]"), "23514", "chk_match_by_state");
        refuses(() -> insertMatch(w, "AUTO", -1L, 0, true, null, false, 90, "[]"), "23514", "chk_match_by_state");
        refuses(() -> insertMatch(w, "AUTO", 1_000_000_000_001L, 0, true, null, false, 90, "[]"), "23514", "chk_match_by_state");
        refuses(() -> insertMatch(w, "CONFIRMED", 5000L, -1, true, w.user, true, 90, "[]"), "23514", "chk_match_by_state");
        refuses(() -> insertMatch(w, "CONFIRMED", 5000L, 1_000_000_000_001L, true, w.user, true, 90, "[]"), "23514", "chk_match_by_state");
    }

    @Test
    void who_decided_and_when_is_tied_to_the_state() {
        MatchWorld w = new MatchWorld();
        refuses(() -> insertMatch(w, "SUGGESTED", null, 0, false, w.user, false, 90, "[]"), "23514", "chk_match_decided");
        refuses(() -> insertMatch(w, "SUGGESTED", null, 0, false, null, true, 90, "[]"), "23514", "chk_match_decided");
        refuses(() -> insertMatch(w, "AUTO", 5000L, 0, true, w.user, false, 90, "[]"), "23514", "chk_match_decided");
        refuses(() -> insertMatch(w, "AUTO", 5000L, 0, true, null, true, 90, "[]"), "23514", "chk_match_decided");
        refuses(() -> insertMatch(w, "CONFIRMED", 5000L, 0, true, null, true, 90, "[]"), "23514", "chk_match_decided");
        refuses(() -> insertMatch(w, "CONFIRMED", 5000L, 0, true, w.user, false, 90, "[]"), "23514", "chk_match_decided");
        refuses(() -> insertMatch(w, "REJECTED", null, 0, false, null, false, 90, "[]"), "23514", "chk_match_decided");
        refuses(() -> insertMatch(w, "REVERSED", 5000L, 0, true, null, true, 90, "[]"), "23514", "chk_match_decided");
    }

    @Test
    void the_score_is_between_0_and_100() {
        MatchWorld w = new MatchWorld();
        UUID order = w.order(5000, "PENDING_PAYMENT", null, null);
        UUID payment = w.payment(5000, "RECEIVED");
        for (int ok : new int[]{0, 100}) {
            assertThatCode(() -> w.run("INSERT INTO match(id, tenant_id, order_id, payment_id, score, reasons_json, state, decided_at) "
                    + "VALUES (?, ?, ?, ?, ?, '[]', 'REJECTED', now())", UUID.randomUUID(), w.tenant, order, payment, ok)).doesNotThrowAnyException();
        }
        for (int bad : new int[]{-1, 101}) {
            refuses(() -> w.run("INSERT INTO match(id, tenant_id, order_id, payment_id, score, reasons_json, state, decided_at) "
                    + "VALUES (?, ?, ?, ?, ?, '[]', 'REJECTED', now())", UUID.randomUUID(), w.tenant, order, payment, bad), "23514", "chk_match_score");
        }
    }

    @Test
    void reasons_hold_codes_and_integers_only() {
        MatchWorld w = new MatchWorld();
        for (ReasonCode code : ReasonCode.values()) {
            String json = Reason.toJson(List.of(new Reason(code, 25)));
            assertThatCode(() -> insertMatch(w, "SUGGESTED", null, 0, false, null, false, 90, json)).as(code.name()).doesNotThrowAnyException();
        }
        for (String bad : new String[]{"[{\"code\":\"Ana K.\"}]", "<b>", "[\"it's\"]", "[\"a\\nb\"]", "[\"caf\u00E9\"]", "[\"a-b\"]", "[\"a.b\"]",
                "[\"=HYPERLINK(1)\"]", "[\"a;b\"]", "x".repeat(2001)}) {
            refuses(() -> insertMatch(w, "SUGGESTED", null, 0, false, null, false, 90, bad), "23514", "chk_match_reasons");
        }
        assertThatCode(() -> insertMatch(w, "SUGGESTED", null, 0, false, null, false, 90, "x".repeat(2000))).doesNotThrowAnyException();
    }

    // ── Status moves ───────────────────────────────────────────────────────

    @Test
    void an_order_moves_only_between_pending_partially_paid_and_paid() throws SQLException {
        MatchWorld w = new MatchWorld();
        Map<String, UUID> orders = Map.of(
                "PENDING_PAYMENT", w.order(5000, "PENDING_PAYMENT", null, null),
                "PARTIALLY_PAID", w.order(5000, "PARTIALLY_PAID", null, null),
                "PAID", w.order(5000, "PAID", null, null),
                "NEEDS_REVIEW", w.order(5000, "NEEDS_REVIEW", null, null),
                "UNPARSED", w.orderWithoutTotal("UNPARSED"),
                "CANCELLED", w.order(5000, "CANCELLED", null, null));
        Set<String> open = Set.of("PENDING_PAYMENT", "PARTIALLY_PAID", "PAID");
        for (Map.Entry<String, UUID> from : orders.entrySet()) {
            for (String to : orders.keySet()) {
                if (from.getKey().equals(to)) {
                    continue;
                }
                Work update = c -> MatchWorld.run(c, "UPDATE orders SET status = ? WHERE id = ?", to, from.getValue());
                if (open.contains(from.getKey()) && open.contains(to)) {
                    assertThatCode(() -> tryOnly(w, update)).as(from.getKey() + " to " + to).doesNotThrowAnyException();
                } else {
                    refuses(() -> tryOnly(w, update), "23514", "is not allowed");
                }
            }
        }
    }

    @Test
    void a_payment_moves_only_between_received_unverified_matched_and_unmatched_as_allowed() throws SQLException {
        MatchWorld w = new MatchWorld();
        UUID received = w.payment(1000, "RECEIVED", true);
        record Start(String status, boolean tx, UUID id) {
        }
        List<Start> starts = List.of(
                new Start("RECEIVED", true, received),
                new Start("UNVERIFIED", false, w.payment(1000, "UNVERIFIED", false)),
                new Start("MATCHED", true, w.payment(1000, "MATCHED", true)),
                new Start("MATCHED", false, w.payment(1000, "MATCHED", false)),
                new Start("UNMATCHED", true, w.payment(1000, "UNMATCHED", true)),
                new Start("DUPLICATE", true, w.duplicateOf(received, 1000)),
                new Start("UNPARSED", false, w.unparsedPayment()));
        Set<String> allowed = Set.of("RECEIVED>MATCHED", "UNVERIFIED>MATCHED", "MATCHED>RECEIVED", "MATCHED>UNVERIFIED", "RECEIVED>UNMATCHED",
                "UNMATCHED>RECEIVED", "UNMATCHED>MATCHED");
        for (Start from : starts) {
            for (String to : List.of("RECEIVED", "UNVERIFIED", "MATCHED", "UNMATCHED", "DUPLICATE", "UNPARSED")) {
                if (from.status().equals(to)) {
                    continue;
                }
                Work update = c -> MatchWorld.run(c, "UPDATE payment SET status = ? WHERE id = ?", to, from.id());
                String pair = from.status() + ">" + to;
                if (!allowed.contains(pair)) {
                    refuses(() -> tryOnly(w, update), "23514", "is not allowed");
                } else if (("RECEIVED".equals(to) && !from.tx()) || ("UNVERIFIED".equals(to) && from.tx())) {
                    // the move is allowed, but the existing rule that ties the status to the transaction id still applies
                    refuses(() -> tryOnly(w, update), "23514", "chk_payment_status_links");
                } else {
                    assertThatCode(() -> tryOnly(w, update)).as(pair).doesNotThrowAnyException();
                }
            }
        }
    }

    @Test
    void a_status_changed_on_its_own_must_leave_the_books_consistent_at_commit() {
        MatchWorld w = new MatchWorld();
        UUID order = w.order(5000, "PENDING_PAYMENT", null, null);
        UUID payment = w.payment(5000, "RECEIVED");
        refuses(() -> tx(w, c -> MatchWorld.run(c, "UPDATE orders SET status = 'PAID' WHERE id = ?", order)), "23514", "but its matches make it PENDING_PAYMENT");
        refuses(() -> tx(w, c -> MatchWorld.run(c, "UPDATE payment SET status = 'MATCHED' WHERE id = ?", payment)), "23514", "but it has 0 active matches");
        UUID matchedOrder = w.order(5000, "PAID", null, null);
        UUID matchedPayment = w.payment(5000, "MATCHED");
        w.activeMatch(matchedOrder, matchedPayment, "AUTO", 5000, 0);
        refuses(() -> tx(w, c -> MatchWorld.run(c, "UPDATE orders SET status = 'PARTIALLY_PAID' WHERE id = ?", matchedOrder)), "23514", "but its matches make it PAID");
        refuses(() -> tx(w, c -> MatchWorld.run(c, "UPDATE payment SET status = 'RECEIVED' WHERE id = ?", matchedPayment)), "23514", "but it has 1 active matches");
    }

    @Test
    void a_match_moves_forward_only_and_its_amounts_are_set_once() throws SQLException {
        MatchWorld w = new MatchWorld();
        UUID suggested = w.suggested(w.order(5000, "PENDING_PAYMENT", null, null), w.payment(5000, "RECEIVED"));
        UUID rejected = w.rejected(w.order(5000, "PENDING_PAYMENT", null, null), w.payment(5000, "RECEIVED"), true);
        UUID autoOrder = w.order(5000, "PAID", null, null);
        UUID auto = w.activeMatch(autoOrder, w.payment(5000, "MATCHED"), "AUTO", 5000, 0);
        UUID confirmed = w.activeMatch(w.order(5000, "PAID", null, null), w.payment(5000, "MATCHED"), "CONFIRMED", 5000, 0);
        UUID reversed = w.reversedMatch(w.order(5000, "PENDING_PAYMENT", null, null), w.payment(5000, "RECEIVED"), 5000);
        Map<String, UUID> rows = Map.of("SUGGESTED", suggested, "REJECTED", rejected, "AUTO", auto, "CONFIRMED", confirmed, "REVERSED", reversed);
        Set<String> allowed = Set.of("SUGGESTED>CONFIRMED", "SUGGESTED>REJECTED", "AUTO>REVERSED", "CONFIRMED>REVERSED");
        for (Map.Entry<String, UUID> from : rows.entrySet()) {
            for (String to : rows.keySet()) {
                if (!from.getKey().equals(to) && !allowed.contains(from.getKey() + ">" + to)) {
                    refuses(() -> tryOnly(w, c -> MatchWorld.run(c, "UPDATE match SET state = ? WHERE id = ?", to, from.getValue())), "23514", "is not allowed");
                }
            }
        }
        UUID txn = ledgerTxnOf(w, auto);
        // the four moves that are allowed, each with the columns it needs
        assertThatCode(() -> tryOnly(w, c -> MatchWorld.run(c,
                "UPDATE match SET state = 'CONFIRMED', decided_by = ?, decided_at = now(), amount_minor = 5000, excess_minor = 0, ledger_txn_id = ? WHERE id = ?",
                w.user, txn, suggested))).doesNotThrowAnyException();
        assertThatCode(() -> tryOnly(w, c -> MatchWorld.run(c, "UPDATE match SET state = 'REJECTED', decided_by = ?, decided_at = now() WHERE id = ?",
                w.user, suggested))).doesNotThrowAnyException();
        assertThatCode(() -> tryOnly(w, c -> MatchWorld.run(c, "UPDATE match SET state = 'REVERSED', decided_by = ?, decided_at = now() WHERE id = ?",
                w.user, auto))).doesNotThrowAnyException();
        assertThatCode(() -> tryOnly(w, c -> MatchWorld.run(c, "UPDATE match SET state = 'REVERSED', decided_by = ?, decided_at = now() WHERE id = ?",
                w.user, confirmed))).doesNotThrowAnyException();
        // nothing changes without a change of state, and the amounts never change after confirmation
        refuses(() -> tryOnly(w, c -> MatchWorld.run(c, "UPDATE match SET amount_minor = 4000 WHERE id = ?", auto)), "23514", "without a change of state");
        refuses(() -> tryOnly(w, c -> MatchWorld.run(c, "UPDATE match SET excess_minor = 1 WHERE id = ?", confirmed)), "23514", "without a change of state");
        refuses(() -> tryOnly(w, c -> MatchWorld.run(c, "UPDATE match SET decided_by = NULL WHERE id = ?", confirmed)), "23514", "without a change of state");
        refuses(() -> tryOnly(w, c -> MatchWorld.run(c, "UPDATE match SET ledger_txn_id = ? WHERE id = ?", txn, confirmed)), "23514", "without a change of state");
        refuses(() -> tryOnly(w, c -> MatchWorld.run(c, "UPDATE match SET decided_at = now() WHERE id = ?", confirmed)), "23514", "without a change of state");
        refuses(() -> tryOnly(w, c -> MatchWorld.run(c,
                "UPDATE match SET state = 'REVERSED', decided_by = ?, decided_at = now(), amount_minor = 1 WHERE id = ?", w.user, auto)),
                "23514", "set once");
        assertThatCode(() -> tryOnly(w, c -> MatchWorld.run(c, "UPDATE match SET state = state WHERE id = ?", auto))).doesNotThrowAnyException();
    }

    private static UUID ledgerTxnOf(MatchWorld w, UUID matchId) throws SQLException {
        try (Connection c = w.owner.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT ledger_txn_id FROM match WHERE id = ?")) {
            ps.setObject(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    // ── What the application role may do ───────────────────────────────────

    private static final Map<String, Set<String>> GRANTED_COLUMNS = Map.of(
            "orders", Set.of("status"),
            "payment", Set.of("status"),
            "match", Set.of("state", "decided_by", "decided_at", "amount_minor", "excess_minor", "ledger_txn_id"));

    private static void asApp(String sql) throws SQLException {
        try (Connection c = MatchWorld.DB.appDataSource().getConnection(); var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void the_application_role_can_update_exactly_the_named_columns_and_no_others() throws SQLException {
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection()) {
            for (Map.Entry<String, Set<String>> table : GRANTED_COLUMNS.entrySet()) {
                List<String> columns = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?")) {
                    ps.setString(1, table.getKey());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            columns.add(rs.getString(1));
                        }
                    }
                }
                assertThat(columns).as("the catalog lists the columns of " + table.getKey()).contains("id", "tenant_id").containsAll(table.getValue());
                try (PreparedStatement ps = c.prepareStatement("SELECT has_column_privilege('chatledger_app', ?, ?, 'UPDATE')")) {
                    for (String column : columns) {
                        ps.setString(1, "public." + table.getKey());
                        ps.setString(2, column);
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            assertThat(rs.getBoolean(1)).as(table.getKey() + "." + column).isEqualTo(table.getValue().contains(column));
                        }
                    }
                }
                for (String column : columns) {
                    String sql = "UPDATE " + table.getKey() + " SET \"" + column + "\" = \"" + column + "\" WHERE false";
                    if (table.getValue().contains(column)) {
                        assertThatCode(() -> asApp(sql)).as(sql).doesNotThrowAnyException();
                    } else {
                        assertThatThrownBy(() -> asApp(sql)).as(sql).isInstanceOfSatisfying(SQLException.class, e -> {
                            assertThat(e.getSQLState()).isEqualTo("42501");
                            assertThat(e.getMessage()).contains("permission denied for table " + table.getKey());
                        });
                    }
                }
            }
        }
    }

    @Test
    void the_application_role_holds_no_other_privilege_on_the_three_tables() throws SQLException {
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT has_table_privilege('chatledger_app', ?, ?)")) {
            for (String table : GRANTED_COLUMNS.keySet()) {
                for (String refused : new String[]{"UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                    ps.setString(1, "public." + table);
                    ps.setString(2, refused);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        assertThat(rs.getBoolean(1)).as(table + " " + refused).isFalse();
                    }
                }
                for (String granted : new String[]{"SELECT", "INSERT"}) {
                    ps.setString(1, "public." + table);
                    ps.setString(2, granted);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        assertThat(rs.getBoolean(1)).as(table + " " + granted).isTrue();
                    }
                }
            }
        }
        for (String sql : new String[]{"DELETE FROM match", "TRUNCATE match", "DELETE FROM orders", "DELETE FROM payment"}) {
            assertThatThrownBy(() -> asApp(sql)).as(sql).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void the_application_role_cannot_move_a_row_to_another_tenant() {
        for (String table : new String[]{"orders", "payment", "match"}) {
            assertThatThrownBy(() -> asApp("UPDATE " + table + " SET tenant_id = tenant_id WHERE false")).as(table)
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
    }
}
