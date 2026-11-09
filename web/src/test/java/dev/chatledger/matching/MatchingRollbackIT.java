package dev.chatledger.matching;

import dev.chatledger.matching.MatchingService.ApplyStep;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.chatledger.matching.MatchingFixture.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a failed half-way run leaves behind: nothing. A failure is injected after each step of the apply transaction, in the
 * seller's-decision path and in the automatic path, and after every one the books, the matches, the statuses, the accounts and the audit
 * trail are exactly as they were; then the same call without the failure succeeds and posts once. Failures the database itself raises
 * (a refused posting, a refused audit row, the commit-time triggers) are tested the same way.
 *
 * <p>Requirements: 3, 4.1, 4.2. Task 6.2.
 */
class MatchingRollbackIT {

    /** Everything a half-way run could have changed, as one comparable text. */
    private static String snapshot(UUID tenant, UUID order, UUID payment) {
        List<String> parts = new ArrayList<>();
        parts.add("ledger=" + MatchingFixture.ledger(tenant));
        parts.add("accounts=" + count("SELECT count(*) FROM account WHERE tenant_id = ?", tenant, null));
        parts.add("txns=" + count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ?", tenant, null));
        parts.add("matches=" + MatchingFixture.rows("SELECT id || ' ' || state || ' ' || COALESCE(amount_minor::text, '-') || ' ' || COALESCE(decided_at::text, '-') "
                + "FROM match WHERE tenant_id = ? ORDER BY id", tenant));
        parts.add("order=" + MatchingFixture.status("orders", order));
        parts.add("payment=" + MatchingFixture.status("payment", payment));
        parts.add("audit=" + count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action LIKE 'MATCH_%'", tenant, null));
        return String.join("\n", parts);
    }

    private static MatchingService.StepHook failAfter(ApplyStep target) {
        return (step, connection) -> {
            if (step == target) {
                throw new IllegalStateException("injected failure after " + step);
            }
        };
    }

    /** A tenant built by hand: an open order that has no ledger posting yet, a free payment, two open suggestions, and no accounts. */
    private static final class ByHand {
        final MatchWorld w = new MatchWorld();
        final UUID order = w.order(5000, "PENDING_PAYMENT", "A-1", null);
        final UUID otherOrder = w.order(5000, "PENDING_PAYMENT", "A-2", null);
        final UUID payment = w.payment(5000, "RECEIVED");
        final UUID suggestion = w.suggested(order, payment);
        final UUID otherSuggestion = w.suggested(otherOrder, payment);
    }

    // ── After each step, by the seller's decision ──────────────────────────

    @Test
    void a_failure_after_any_step_of_a_confirmation_leaves_nothing_and_the_retry_posts_once() {
        for (ApplyStep step : ApplyStep.values()) {
            if (step == ApplyStep.FLAGGED) {
                continue;   // reached only by a flagged pair, which is never applied: FlagMatchingIT fails it on purpose
            }
            ByHand h = new ByHand();
            String before = snapshot(h.w.tenant, h.order, h.payment);
            assertThat(before).contains("accounts=0", "txns=0", "audit=0");
            MatchingFixture failing = new MatchingFixture(MatchConfig.defaults(), failAfter(step), false);
            assertThatThrownBy(() -> failing.matcher.apply(h.w.tenant, h.order, h.payment, MatchingService.Mode.CONFIRM, h.w.user))
                    .as("after " + step).hasMessageContaining("injected failure after " + step);
            assertThat(snapshot(h.w.tenant, h.order, h.payment)).as("nothing is left after " + step).isEqualTo(before);

            MatchingFixture working = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
            MatchingService.ApplyResult retry = working.matcher.apply(h.w.tenant, h.order, h.payment, MatchingService.Mode.CONFIRM, h.w.user);
            assertThat(retry.outcome()).as("the retry after " + step).isEqualTo(MatchingService.Outcome.APPLIED);
            assertThat(MatchingFixture.ledger(h.w.tenant)).as("posted once after " + step).containsExactly("ORDER_ACCEPTED 1100 D5000",
                    "ORDER_ACCEPTED 4000 C5000", "PAYMENT_MATCHED 1000 D5000", "PAYMENT_MATCHED 1100 C5000");
            assertThat(count("SELECT count(*) FROM account WHERE tenant_id = ?", h.w.tenant, null)).isEqualTo(4);
            assertThat(MatchingFixture.trialBalance(h.w.tenant)).isZero();
            assertThat(MatchingFixture.status("orders", h.order)).isEqualTo("PAID");
        }
    }

    // ── After each step, by the engine ─────────────────────────────────────

    @Test
    void a_failure_after_any_step_of_an_automatic_match_leaves_nothing_and_the_retry_posts_once() {
        for (ApplyStep step : ApplyStep.values()) {
            if (step == ApplyStep.FLAGGED) {
                continue;   // reached only by a flagged pair, which is never applied: FlagMatchingIT fails it on purpose
            }
            MatchWorld w = new MatchWorld();
            UUID seller = MatchingFixture.user(w.tenant, "SELLER");
            MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
            UUID order = quiet.orders.ingest(w.tenant, seller, MatchingFixture.id(), MatchingFixture.orderText("A-1042", "Ana Kamga", MatchingFixture.PHONE, 5000)).orderId();
            UUID payment = quiet.payments.ingest(w.tenant, seller, MatchingFixture.id(), quiet.sms(5000, "Ana Kamga", MatchingFixture.PHONE, "A-1042")).paymentId();
            w.suggested(order, payment);   // an earlier run's suggestion for the same pair, which the automatic match replaces
            String before = snapshot(w.tenant, order, payment);

            MatchingFixture failing = new MatchingFixture(MatchConfig.defaults(), failAfter(step), false);
            assertThatThrownBy(() -> failing.matcher.apply(w.tenant, order, payment, MatchingService.Mode.AUTO, null))
                    .as("after " + step).hasMessageContaining("injected failure after " + step);
            assertThat(snapshot(w.tenant, order, payment)).as("nothing is left after " + step).isEqualTo(before);

            MatchingService.ApplyResult retry = quiet.matcher.apply(w.tenant, order, payment, MatchingService.Mode.AUTO, null);
            assertThat(retry.outcome()).as("the retry after " + step).isEqualTo(MatchingService.Outcome.APPLIED);
            assertThat(MatchingFixture.ledger(w.tenant)).containsExactly("ORDER_ACCEPTED 1100 D5000", "ORDER_ACCEPTED 4000 C5000",
                    "PAYMENT_MATCHED 1000 D5000", "PAYMENT_MATCHED 1100 C5000");
            assertThat(count("SELECT count(*) FROM match WHERE tenant_id = ? AND state = 'AUTO'", w.tenant, null)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM match WHERE tenant_id = ? AND state = 'SUGGESTED'", w.tenant, null)).isZero();
        }
    }

    // ── The database as the last wall ──────────────────────────────────────

    private static void commitTimeFailure(String name, String sqlAfter, ApplyStep step) {
        ByHand h = new ByHand();
        String before = snapshot(h.w.tenant, h.order, h.payment);
        MatchingService.StepHook tamper = (s, c) -> {
            if (s == step) {
                try (Statement st = c.createStatement()) {
                    st.execute(sqlAfter.replace("{order}", "'" + h.order + "'").replace("{payment}", "'" + h.payment + "'"));
                }
            }
        };
        MatchingFixture failing = new MatchingFixture(MatchConfig.defaults(), tamper, false);
        assertThatThrownBy(() -> failing.matcher.apply(h.w.tenant, h.order, h.payment, MatchingService.Mode.CONFIRM, h.w.user))
                .as(name).isInstanceOf(IllegalStateException.class).hasMessage("Could not apply a match").hasRootCauseInstanceOf(SQLException.class);
        assertThat(snapshot(h.w.tenant, h.order, h.payment)).as("nothing is left: " + name).isEqualTo(before);
    }

    @Test
    void if_the_books_would_be_inconsistent_the_commit_is_refused_and_everything_rolls_back() {
        commitTimeFailure("order status put back", "UPDATE orders SET status = 'PENDING_PAYMENT' WHERE id = {order}", ApplyStep.AUDIT);
        commitTimeFailure("payment status put back", "UPDATE payment SET status = 'RECEIVED' WHERE id = {payment}", ApplyStep.AUDIT);
        commitTimeFailure("a second posting with no entries",
                "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) SELECT gen_random_uuid(), tenant_id, 'PAYMENT_MATCHED', 'MATCH', gen_random_uuid() FROM payment WHERE id = {payment}",
                ApplyStep.AUDIT);
    }

    private static void blockWith(UUID tenant, String table, String name) throws SQLException {
        try (Connection c = MatchingFixture.DB.migrateDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + name + " CHECK (tenant_id <> '" + tenant + "'::uuid) NOT VALID");
        }
    }

    private static void unblock(String table, String name) throws SQLException {
        try (Connection c = MatchingFixture.DB.migrateDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + name);
        }
    }

    @Test
    void a_posting_the_database_refuses_leaves_nothing_and_the_retry_after_the_fault_is_cleared_posts_once() throws SQLException {
        ByHand h = new ByHand();
        String before = snapshot(h.w.tenant, h.order, h.payment);
        String name = "block_" + UUID.randomUUID().toString().replace("-", "");
        blockWith(h.w.tenant, "ledger_txn", name);
        MatchingFixture f = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
        assertThatThrownBy(() -> f.matcher.apply(h.w.tenant, h.order, h.payment, MatchingService.Mode.CONFIRM, h.w.user))
                .isInstanceOf(IllegalStateException.class).hasMessage("Could not apply a match");
        assertThat(snapshot(h.w.tenant, h.order, h.payment)).isEqualTo(before);
        unblock("ledger_txn", name);
        assertThat(f.matcher.apply(h.w.tenant, h.order, h.payment, MatchingService.Mode.CONFIRM, h.w.user).outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        assertThat(MatchingFixture.ledger(h.w.tenant)).hasSize(4);
    }

    @Test
    void an_audit_row_the_database_refuses_leaves_nothing_and_the_retry_after_the_fault_is_cleared_posts_once() throws SQLException {
        ByHand h = new ByHand();
        String before = snapshot(h.w.tenant, h.order, h.payment);
        String name = "block_" + UUID.randomUUID().toString().replace("-", "");
        blockWith(h.w.tenant, "audit_log", name);
        MatchingFixture f = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
        assertThatThrownBy(() -> f.matcher.apply(h.w.tenant, h.order, h.payment, MatchingService.Mode.CONFIRM, h.w.user))
                .isInstanceOf(IllegalStateException.class).hasMessage("Could not apply a match");
        assertThat(snapshot(h.w.tenant, h.order, h.payment)).isEqualTo(before);
        unblock("audit_log", name);
        assertThat(f.matcher.apply(h.w.tenant, h.order, h.payment, MatchingService.Mode.CONFIRM, h.w.user).outcome()).isEqualTo(MatchingService.Outcome.APPLIED);
        assertThat(MatchingFixture.ledger(h.w.tenant)).hasSize(4);
    }

    @Test
    void a_refused_chart_of_accounts_posts_nothing() {
        ByHand h = new ByHand();
        h.w.run("INSERT INTO account(id, tenant_id, code, name, type) VALUES (?, ?, '1100', 'Wrong', 'LIABILITY')", UUID.randomUUID(), h.w.tenant);
        MatchingFixture f = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
        assertThatThrownBy(() -> f.matcher.apply(h.w.tenant, h.order, h.payment, MatchingService.Mode.CONFIRM, h.w.user))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT count(*) FROM ledger_txn WHERE tenant_id = ?", h.w.tenant, null)).isZero();
        assertThat(count("SELECT count(*) FROM account WHERE tenant_id = ?", h.w.tenant, null)).as("only the wrong one the test made").isEqualTo(1);
        assertThat(MatchingFixture.status("payment", h.payment)).isEqualTo("RECEIVED");
    }

    @Test
    void the_engine_never_lets_a_failure_reach_the_caller_and_leaves_the_payment_stored_and_unmatched() {
        MatchWorld w = new MatchWorld();
        UUID seller = MatchingFixture.user(w.tenant, "SELLER");
        MatchingFixture quiet = new MatchingFixture(MatchConfig.defaults(), MatchingService.StepHook.NONE, false);
        quiet.orders.ingest(w.tenant, seller, MatchingFixture.id(), MatchingFixture.orderText("A-1042", "Ana Kamga", MatchingFixture.PHONE, 5000));
        MatchingFixture failing = new MatchingFixture(MatchConfig.defaults(), failAfter(ApplyStep.MATCH_POSTED), true);
        var result = failing.payments.ingest(w.tenant, seller, MatchingFixture.id(), failing.sms(5000, "Ana Kamga", MatchingFixture.PHONE, "A-1042"));
        assertThat(result.outcome()).as("ingestion is not affected by the matcher's failure").isEqualTo(dev.chatledger.payments.PaymentIngestionResult.Outcome.CREATED);
        assertThat(MatchingFixture.status("payment", result.paymentId())).isEqualTo("RECEIVED");
        assertThat(count("SELECT count(*) FROM match WHERE tenant_id = ? AND state IN ('AUTO', 'CONFIRMED')", w.tenant, null)).isZero();
        // a later run (the next event, or an explicit rematch in stop 3) matches it
        assertThat(quiet.matcher.onPaymentRecorded(w.tenant, result.paymentId()).kind()).isEqualTo(MatchingService.RunKind.APPLIED);
    }
}
