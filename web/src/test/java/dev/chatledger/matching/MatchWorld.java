package dev.chatledger.matching;

import dev.chatledger.ledger.LedgerDatabase;
import dev.chatledger.tenancy.TestHashes;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds orders, payments, matches and postings with plain SQL as the schema owner, so a test can set up exactly the state it wants
 * (including states the application would never produce) and see what the database accepts. Everything is synthetic.
 */
final class MatchWorld {

    static final LedgerDatabase DB = LedgerDatabase.get();
    static final String HASH = "ab".repeat(32);

    final UUID tenant = DB.createTenant();
    final UUID user = newUser(tenant);
    final DataSource owner = DB.migrateDataSource();
    private int counter;

    private static UUID newUser(UUID tenant) {
        UUID id = UUID.randomUUID();
        try (Connection c = DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'SELLER')")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setString(3, "user-" + id + "@example.test");
            ps.setString(4, TestHashes.UNUSABLE);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private String next(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "-" + (counter++);
    }

    UUID customer(String name, String phoneHash, String last4) {
        UUID id = UUID.randomUUID();
        run("INSERT INTO customer(id, tenant_id, display_name, phone_hash, phone_last4) VALUES (?, ?, ?, ?, ?)",
                id, tenant, name, phoneHash, last4);
        return id;
    }

    UUID order(long total, String status, String ref, UUID customer) {
        return order(total, status, ref, customer, "XAF", Instant.now());
    }

    UUID order(long total, String status, String ref, UUID customer, String currency, Instant createdAt) {
        UUID id = UUID.randomUUID();
        run("INSERT INTO orders(id, tenant_id, external_id, order_ref, customer_id, total_minor, currency, status, raw_text, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'synthetic order', ?)",
                id, tenant, next("ord"), ref, customer, total, currency, status, Timestamp.from(createdAt));
        return id;
    }

    /** An order with no total (NEEDS_REVIEW or UNPARSED). */
    UUID orderWithoutTotal(String status) {
        UUID id = UUID.randomUUID();
        run("INSERT INTO orders(id, tenant_id, external_id, total_minor, currency, status, raw_text) "
                        + "VALUES (?, ?, ?, NULL, 'XAF', ?, 'synthetic order')",
                id, tenant, next("ord"), status);
        return id;
    }

    /** A RECEIVED, UNVERIFIED, MATCHED or UNMATCHED payment ({@code withTx} says whether it has a transaction id). */
    UUID payment(long amount, String status, boolean withTx, String reference, String senderName, String phoneHash, String last4,
                 String currency, Instant createdAt) {
        UUID id = UUID.randomUUID();
        run("INSERT INTO payment(id, tenant_id, external_id, provider, tx_id, amount_minor, currency, sender_name, sender_phone_hash, "
                        + "sender_last4, reference, paid_at, status, raw_text, created_at) "
                        + "VALUES (?, ?, ?, 'PROVIDER_A', ?, ?, ?, ?, ?, ?, ?, now(), ?, 'synthetic confirmation', ?)",
                id, tenant, next("pay"), withTx ? "TX" + UUID.randomUUID().toString().substring(0, 12) : null, amount, currency, senderName,
                phoneHash, last4, reference, status, Timestamp.from(createdAt));
        return id;
    }

    UUID payment(long amount, String status, boolean withTx) {
        return payment(amount, status, withTx, null, null, null, null, "XAF", Instant.now());
    }

    UUID payment(long amount, String status) {
        return payment(amount, status, !"UNVERIFIED".equals(status));
    }

    /** A DUPLICATE payment pointing at an existing original. */
    UUID duplicateOf(UUID original, long amount) {
        UUID id = UUID.randomUUID();
        run("INSERT INTO payment(id, tenant_id, external_id, provider, tx_id, amount_minor, currency, paid_at, status, raw_text, duplicate_of) "
                        + "VALUES (?, ?, ?, 'PROVIDER_A', ?, ?, 'XAF', now(), 'DUPLICATE', 'synthetic', ?)",
                id, tenant, next("pay"), "TX" + UUID.randomUUID().toString().substring(0, 12), amount, original);
        return id;
    }

    /** The posting of a match: debit clearing, credit receivable, credit customer credit (when above zero). Returns the transaction id. */
    UUID posting(Connection c, ChartOfAccounts.Accounts a, UUID matchId, String kind, long debitClearing, long creditReceivable,
                 long creditCustomerCredit) throws SQLException {
        UUID txn = UUID.randomUUID();
        run(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id) VALUES (?, ?, ?, 'MATCH', ?)", txn, tenant, kind, matchId);
        entry(c, txn, a.clearing(), debitClearing, 0);
        entry(c, txn, a.receivable(), 0, creditReceivable);
        entry(c, txn, a.customerCredit(), 0, creditCustomerCredit);
        return txn;
    }

    private void entry(Connection c, UUID txn, UUID account, long debit, long credit) throws SQLException {
        if (debit > 0 || credit > 0) {
            run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), txn, account, debit, credit);
        }
    }

    /** A match row. {@code amount} null means no amount (a suggestion or a rejection). */
    void match(Connection c, UUID id, UUID order, UUID payment, String state, Long amount, long excess, UUID ledgerTxn, UUID decidedBy,
               boolean decided, String reasons) throws SQLException {
        run(c, "INSERT INTO match(id, tenant_id, order_id, payment_id, amount_minor, excess_minor, score, reasons_json, state, ledger_txn_id, "
                        + "decided_by, decided_at) VALUES (?, ?, ?, ?, ?, ?, 90, ?, ?, ?, ?, ?)",
                id, tenant, order, payment, amount, excess, reasons, state, ledgerTxn, decidedBy, decided ? Timestamp.from(Instant.now()) : null);
    }

    /**
     * A complete, valid active match in one transaction: the posting, the match, and the statuses already in place. The caller made the
     * order and payment with the statuses they have after the match.
     */
    UUID activeMatch(UUID order, UUID payment, String state, long amount, long excess) {
        UUID id = UUID.randomUUID();
        try (Connection c = owner.getConnection()) {
            c.setAutoCommit(false);
            ChartOfAccounts.Accounts a = ChartOfAccounts.ensure(c, tenant);
            UUID txn = posting(c, a, id, "PAYMENT_MATCHED", amount + excess, amount, excess);
            match(c, id, order, payment, state, amount, excess, txn, "AUTO".equals(state) ? null : user, "AUTO".equals(state) ? false : true, "[]");
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    /** A SUGGESTED match: no amount, no posting, no decision. */
    UUID suggested(UUID order, UUID payment) {
        UUID id = UUID.randomUUID();
        run("INSERT INTO match(id, tenant_id, order_id, payment_id, score, reasons_json, state) VALUES (?, ?, ?, ?, 70, '[]', 'SUGGESTED')",
                id, tenant, order, payment);
        return id;
    }

    /** A REJECTED match; {@code bySeller} false means superseded by the system (no decider). */
    UUID rejected(UUID order, UUID payment, boolean bySeller) {
        UUID id = UUID.randomUUID();
        run("INSERT INTO match(id, tenant_id, order_id, payment_id, score, reasons_json, state, decided_by, decided_at) "
                        + "VALUES (?, ?, ?, ?, 70, '[]', 'REJECTED', ?, now())",
                id, tenant, order, payment, bySeller ? user : null);
        return id;
    }

    /**
     * A REVERSED match with its posting and the reversal of that posting. The order must be open (PENDING_PAYMENT) and the payment
     * RECEIVED, as they are after an unmatch.
     */
    UUID reversedMatch(UUID order, UUID payment, long amount) {
        UUID id = UUID.randomUUID();
        try (Connection c = owner.getConnection()) {
            c.setAutoCommit(false);
            ChartOfAccounts.Accounts a = ChartOfAccounts.ensure(c, tenant);
            UUID txn = posting(c, a, id, "PAYMENT_MATCHED", amount, amount, 0);
            match(c, id, order, payment, "AUTO", amount, 0, txn, null, false, "[]");
            UUID reversal = UUID.randomUUID();
            run(c, "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id, reverses_txn_id) VALUES (?, ?, 'REVERSAL', 'LEDGER_TXN', ?, ?)",
                    reversal, tenant, txn, txn);
            run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, 0, ?)",
                    UUID.randomUUID(), reversal, a.clearing(), amount);
            run(c, "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) VALUES (?, ?, ?, ?, 0)",
                    UUID.randomUUID(), reversal, a.receivable(), amount);
            run(c, "UPDATE match SET state = 'REVERSED', decided_by = ?, decided_at = now() WHERE id = ?", user, id);
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    /** An UNPARSED payment: nothing read, only the text and the tenant's currency. */
    UUID unparsedPayment() {
        UUID id = UUID.randomUUID();
        run("INSERT INTO payment(id, tenant_id, external_id, currency, status, raw_text) VALUES (?, ?, ?, 'XAF', 'UNPARSED', 'synthetic')",
                id, tenant, next("pay"));
        return id;
    }

    void run(String sql, Object... params) {
        try (Connection c = owner.getConnection()) {
            run(c, sql, params);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static void run(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] == null) {
                    ps.setNull(i + 1, Types.NULL);
                } else {
                    ps.setObject(i + 1, params[i]);
                }
            }
            ps.executeUpdate();
        }
    }
}
