package dev.chatledger.ledger.jdbc;

import dev.chatledger.ledger.Account;
import dev.chatledger.ledger.AccountBalance;
import dev.chatledger.ledger.AccountLookup;
import dev.chatledger.ledger.AccountType;
import dev.chatledger.ledger.AlreadyReversedException;
import dev.chatledger.ledger.LedgerEntry;
import dev.chatledger.ledger.LedgerChecks;
import dev.chatledger.ledger.LedgerException;
import dev.chatledger.ledger.LedgerRepository;
import dev.chatledger.ledger.LedgerTransaction;
import dev.chatledger.ledger.Side;
import dev.chatledger.tenancy.DbTime;
import dev.chatledger.tenancy.TenantScope;
import org.postgresql.util.PSQLException;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of the ledger port, written with plain JDBC.
 *
 * <p>It is meant to run on a DataSource that connects as the application role
 * (INSERT and SELECT only, not the owner). It never issues UPDATE or DELETE.
 * Every call runs inside a {@link TenantScope}: one transaction in which the
 * tenant is set first, so row-level security applies. Every query also names
 * the tenant, and reads join through {@code ledger_txn.tenant_id}, so another
 * tenant's rows are never returned even if one layer were missing.
 *
 * <p>A transaction is written in one database transaction. The balance check is
 * a deferred constraint trigger, so an unbalanced write fails when the scope
 * commits and nothing is persisted.
 */
public final class JdbcLedgerRepository implements LedgerRepository {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";
    private static final String UQ_REVERSAL = "uq_ledger_txn_reverses";

    private final TenantScope scope;

    public JdbcLedgerRepository(DataSource dataSource) {
        this(new TenantScope(dataSource));
    }

    public JdbcLedgerRepository(TenantScope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public void createAccount(Account account) {
        try {
            scope.call(account.tenantId(), c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO account(id, tenant_id, code, name, type) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setObject(1, account.id());
                    ps.setObject(2, account.tenantId());
                    ps.setString(3, account.code());
                    ps.setString(4, account.name());
                    ps.setString(5, account.type().name());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw translate("Could not create account " + account.id(), e);
        }
    }

    @Override
    public Optional<Account> findAccount(UUID tenantId, UUID accountId) {
        try {
            return scope.call(tenantId, c -> readAccount(c, tenantId, accountId));
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read account " + accountId, e);
        }
    }

    private static Optional<Account> readAccount(Connection c, UUID tenantId, UUID accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, tenant_id, code, name, type FROM account WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.<Account>empty();
                }
                return Optional.of(new Account(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getString("code"), rs.getString("name"),
                        AccountType.valueOf(rs.getString("type"))));
            }
        }
    }

    @Override
    public void append(LedgerTransaction txn) {
        try {
            scope.call(txn.tenantId(), c -> {
                write(c, txn);
                return null;   // the scope commits; the deferred balance trigger fires there
            });
        } catch (SQLException e) {
            throw translate(txn, e);
        }
    }

    /**
     * Posts a transaction on the caller's connection, in the caller's transaction, so that it commits or rolls back together with
     * whatever else the caller wrote (a match, a status change). The connection must already be scoped to the transaction's tenant
     * ({@link TenantScope}). The checks are the ones {@code LedgerService} makes ({@link LedgerChecks}); the deferred balance trigger
     * still fires when the caller commits. A reversal is not posted this way (it is added with the unmatch flow).
     *
     * @throws dev.chatledger.ledger.LedgerException if a check fails or the database refuses a row
     */
    public void appendWithin(Connection c, LedgerTransaction txn) {
        if (txn.isReversal()) {
            throw new LedgerException("appendWithin does not post a reversal");
        }
        write(c, txn);
    }

    /**
     * Posts the reversal of a transaction of the tenant on the caller's connection, in the caller's transaction (an unmatch reverses the
     * posting of its match and changes the match, the order and the payment together). The original is read, never changed; the reversal
     * is its mirror, linked to it. The same checks as {@code LedgerService.reverse}: the original must exist for this tenant, must not
     * itself be a reversal, and must not already be reversed (a second reversal is also refused by the unique index, which is the
     * guard against a race; a database error would abort the caller's transaction, so the explicit check comes first).
     *
     * @throws AlreadyReversedException if it was reversed before
     * @throws LedgerException if it does not exist for this tenant, or is itself a reversal
     */
    public LedgerTransaction reverseWithin(Connection c, UUID tenantId, UUID txnId, UUID reversalId, Instant at) {
        try {
            LedgerTransaction original = readTransaction(c, tenantId, txnId)
                    .orElseThrow(() -> new LedgerException("Transaction not found for this tenant: " + txnId));
            LedgerTransaction reversal = original.reverse(reversalId, DbTime.micros(at));   // the reversal returned is the one stored
            if (reversedWithin(c, tenantId, txnId)) {
                throw new AlreadyReversedException(txnId);
            }
            write(c, reversal);
            return reversal;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not reverse transaction " + txnId, e);
        }
    }

    private static boolean reversedWithin(Connection c, UUID tenantId, UUID txnId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM ledger_txn WHERE tenant_id = ? AND reverses_txn_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, txnId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void write(Connection c, LedgerTransaction txn) {
        LedgerChecks.verify(txn, new ConnectionLookup(c));
        try {
            insertHeader(c, txn);
            insertEntries(c, txn);
        } catch (SQLException e) {
            throw translate(txn, e);
        }
    }

    /** Reads on one connection, for the pre-posting checks. */
    private static final class ConnectionLookup implements AccountLookup {
        private final Connection connection;

        ConnectionLookup(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Optional<Account> findAccount(UUID tenantId, UUID accountId) {
            try {
                return readAccount(connection, tenantId, accountId);
            } catch (SQLException e) {
                throw new IllegalStateException("Could not read account " + accountId, e);
            }
        }

        @Override
        public long netDebit(UUID tenantId, UUID accountId) {
            try {
                return readNetDebit(connection, tenantId, accountId);
            } catch (SQLException e) {
                throw new IllegalStateException("Could not read balance of " + accountId, e);
            }
        }
    }

    private void insertHeader(Connection c, LedgerTransaction txn) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_txn(id, tenant_id, kind, ref_type, ref_id, reverses_txn_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, txn.id());
            ps.setObject(2, txn.tenantId());
            ps.setString(3, txn.kind());
            ps.setString(4, txn.refType());
            ps.setObject(5, txn.refId());
            ps.setObject(6, txn.reversesTxnId());
            ps.setObject(7, DbTime.utc(txn.createdAt()));
            ps.executeUpdate();
        }
    }

    private void insertEntries(Connection c, LedgerTransaction txn) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_entry(id, txn_id, account_id, debit_minor, credit_minor) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            for (LedgerEntry e : txn.entries()) {
                ps.setObject(1, e.id());
                ps.setObject(2, txn.id());
                ps.setObject(3, e.accountId());
                ps.setLong(4, e.side() == Side.DEBIT ? e.amountMinor() : 0L);
                ps.setLong(5, e.side() == Side.CREDIT ? e.amountMinor() : 0L);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public Optional<LedgerTransaction> findTransaction(UUID tenantId, UUID txnId) {
        try {
            return scope.call(tenantId, c -> readTransaction(c, tenantId, txnId));
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read transaction " + txnId, e);
        }
    }

    private Optional<LedgerTransaction> readTransaction(Connection c, UUID tenantId, UUID txnId)
            throws SQLException {
        String kind;
        String refType;
        UUID refId;
        UUID reverses;
        Instant createdAt;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT kind, ref_type, ref_id, reverses_txn_id, created_at "
                        + "FROM ledger_txn WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, txnId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                kind = rs.getString("kind");
                refType = rs.getString("ref_type");
                refId = rs.getObject("ref_id", UUID.class);
                reverses = rs.getObject("reverses_txn_id", UUID.class);
                createdAt = rs.getObject("created_at", OffsetDateTime.class).toInstant();
            }
        }
        List<LedgerEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT e.id, e.account_id, e.debit_minor, e.credit_minor "
                        + "FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id "
                        + "WHERE t.tenant_id = ? AND e.txn_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, txnId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long debit = rs.getLong("debit_minor");
                    Side side = debit > 0 ? Side.DEBIT : Side.CREDIT;
                    long amount = debit > 0 ? debit : rs.getLong("credit_minor");
                    entries.add(new LedgerEntry(rs.getObject("id", UUID.class),
                            rs.getObject("account_id", UUID.class), side, amount));
                }
            }
        }
        return Optional.of(new LedgerTransaction(
                txnId, tenantId, kind, refType, refId, reverses, createdAt, entries));
    }

    @Override
    public boolean isReversed(UUID tenantId, UUID txnId) {
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM ledger_txn WHERE tenant_id = ? AND reverses_txn_id = ?")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, txnId);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next();
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check reversal of " + txnId, e);
        }
    }

    @Override
    public long netDebit(UUID tenantId, UUID accountId) {
        try {
            return scope.call(tenantId, c -> readNetDebit(c, tenantId, accountId));
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read balance of " + accountId, e);
        }
    }

    private static long readNetDebit(Connection c, UUID tenantId, UUID accountId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(e.debit_minor), 0) - COALESCE(SUM(e.credit_minor), 0) "
                        + "FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id "
                        + "WHERE t.tenant_id = ? AND e.account_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return exactLong(rs.getBigDecimal(1));
            }
        }
    }

    @Override
    public List<AccountBalance> balances(UUID tenantId) {
        // The tenant is in the join as well as in the WHERE, so entries are
        // reached only through this tenant's transactions. The SQL is a literal
        // inside prepareStatement so the build can verify nothing is string-built.
        try {
            return scope.call(tenantId, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT a.id, a.type, "
                                + "COALESCE(SUM(e.debit_minor), 0) - COALESCE(SUM(e.credit_minor), 0) AS net "
                                + "FROM account a "
                                + "LEFT JOIN (ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id AND t.tenant_id = ?) "
                                + "       ON e.account_id = a.id "
                                + "WHERE a.tenant_id = ? GROUP BY a.id, a.type ORDER BY a.code")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, tenantId);
                    List<AccountBalance> result = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            result.add(new AccountBalance(rs.getObject("id", UUID.class),
                                    AccountType.valueOf(rs.getString("type")),
                                    exactLong(rs.getBigDecimal("net"))));
                        }
                    }
                    return result;
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read balances", e);
        }
    }

    // ── Error handling ──────────────────────────────────────────────────────

    private static long exactLong(BigDecimal value) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException overflow) {
            throw new LedgerException("Balance does not fit in a long", overflow);
        }
    }

    private static RuntimeException translate(LedgerTransaction txn, SQLException e) {
        if (UNIQUE_VIOLATION.equals(e.getSQLState()) && UQ_REVERSAL.equals(constraintOf(e))) {
            return new AlreadyReversedException(txn.reversesTxnId(), e);
        }
        return translate("Could not write ledger transaction " + txn.id(), e);
    }

    private static RuntimeException translate(String context, SQLException e) {
        String state = e.getSQLState();
        if (CHECK_VIOLATION.equals(state) || FOREIGN_KEY_VIOLATION.equals(state)
                || UNIQUE_VIOLATION.equals(state)) {
            return new LedgerException(e.getMessage(), e);
        }
        return new IllegalStateException(context, e);
    }

    private static String constraintOf(SQLException e) {
        return e instanceof PSQLException p && p.getServerErrorMessage() != null
                ? p.getServerErrorMessage().getConstraint() : null;
    }
}
