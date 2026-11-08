package dev.chatledger.ledger;

import dev.chatledger.ledger.jdbc.JdbcLedgerRepository;
import dev.chatledger.tenancy.TenantScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A posting made on the caller's own connection commits or rolls back together with what else the caller wrote, and goes through the
 * same checks as a posting made through the service.
 *
 * <p>Requirements: 3, 4.1, 4.3. Task 6.1.
 */
class LedgerAppendWithinIT {

    private static final LedgerDatabase DB = LedgerDatabase.get();
    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private final TenantScope scope = new TenantScope(DB.appDataSource());
    private final JdbcLedgerRepository repository = new JdbcLedgerRepository(scope);
    private UUID tenant;
    private Account receivable;
    private Account revenue;

    @BeforeEach
    void fresh() {
        tenant = DB.createTenant();
        receivable = account(tenant, "AR", AccountType.ASSET);
        revenue = account(tenant, "REV", AccountType.REVENUE);
    }

    private Account account(UUID owner, String code, AccountType type) {
        Account a = new Account(UUID.randomUUID(), owner, code, code, type);
        repository.createAccount(a);
        return a;
    }

    private LedgerTransaction sale(long amount) {
        return new LedgerTransaction(UUID.randomUUID(), tenant, "ORDER_ACCEPTED", "ORDER", UUID.randomUUID(), null, NOW,
                List.of(LedgerEntry.debit(receivable.id(), amount), LedgerEntry.credit(revenue.id(), amount)));
    }

    private void auditRow(java.sql.Connection c, String subject) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_log(id, tenant_id, action, subject) VALUES (?, ?, 'TEST', ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            ps.setString(3, subject);
            ps.executeUpdate();
        }
    }

    private long auditRows(String subject) throws SQLException {
        return scope.call(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND subject = ?")) {
                ps.setObject(1, tenant);
                ps.setString(2, subject);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @Test
    void a_posting_commits_together_with_the_other_writes_of_the_caller() throws SQLException {
        LedgerTransaction txn = sale(5000);
        scope.call(tenant, c -> {
            repository.appendWithin(c, txn);
            auditRow(c, "together");
            return null;
        });
        assertThat(repository.findTransaction(tenant, txn.id())).isPresent();
        assertThat(auditRows("together")).isEqualTo(1);
        assertThat(repository.netDebit(tenant, receivable.id())).isEqualTo(5000);
    }

    @Test
    void a_failure_after_the_posting_rolls_the_posting_back_with_everything_else() throws SQLException {
        LedgerTransaction txn = sale(5000);
        assertThatThrownBy(() -> scope.call(tenant, c -> {
            repository.appendWithin(c, txn);
            auditRow(c, "rolled-back");
            throw new IllegalStateException("simulated failure after the posting");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.findTransaction(tenant, txn.id())).isEmpty();
        assertThat(repository.netDebit(tenant, receivable.id())).isZero();
        assertThat(auditRows("rolled-back")).isZero();
    }

    @Test
    void a_failure_of_another_write_before_the_posting_leaves_no_posting() {
        LedgerTransaction txn = sale(5000);
        assertThatThrownBy(() -> scope.call(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_log(id, tenant_id, action, subject) VALUES (?, ?, 'TEST', NULL)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, tenant);
                ps.executeUpdate();   // subject is NOT NULL: refused
            }
            repository.appendWithin(c, txn);
            return null;
        })).isInstanceOf(SQLException.class);
        assertThat(repository.findTransaction(tenant, txn.id())).isEmpty();
    }

    @Test
    void two_postings_on_one_connection_see_each_other() throws SQLException {
        scope.call(tenant, c -> {
            repository.appendWithin(c, sale(100));
            repository.appendWithin(c, sale(200));
            return null;
        });
        assertThat(repository.netDebit(tenant, receivable.id())).isEqualTo(300);
    }

    @Test
    void an_account_of_another_tenant_is_refused_and_nothing_is_written() {
        UUID other = DB.createTenant();
        Account foreign = account(other, "AR", AccountType.ASSET);
        LedgerTransaction txn = new LedgerTransaction(UUID.randomUUID(), tenant, "ORDER_ACCEPTED", "ORDER", UUID.randomUUID(), null, NOW,
                List.of(LedgerEntry.debit(foreign.id(), 100), LedgerEntry.credit(revenue.id(), 100)));
        assertThatThrownBy(() -> scope.call(tenant, c -> {
            repository.appendWithin(c, txn);
            return null;
        })).isInstanceOf(LedgerException.class).hasMessageContaining("does not belong to tenant");
        assertThat(repository.findTransaction(tenant, txn.id())).isEmpty();
    }

    @Test
    void a_balance_that_would_overflow_is_refused_before_anything_is_written() throws SQLException {
        scope.call(tenant, c -> {
            repository.appendWithin(c, sale(Long.MAX_VALUE - 10));
            return null;
        });
        LedgerTransaction toomuch = sale(11);
        assertThatThrownBy(() -> scope.call(tenant, c -> {
            repository.appendWithin(c, toomuch);
            return null;
        })).isInstanceOf(LedgerException.class).hasMessageContaining("overflow");
        assertThat(repository.findTransaction(tenant, toomuch.id())).isEmpty();
        scope.call(tenant, c -> {
            repository.appendWithin(c, sale(10));
            return null;
        });
        assertThat(repository.netDebit(tenant, receivable.id())).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void a_reversal_is_not_posted_this_way() {
        LedgerTransaction original = sale(100);
        new LedgerService(repository).post(original);
        LedgerTransaction reversal = original.reverse(UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> scope.call(tenant, c -> {
            repository.appendWithin(c, reversal);
            return null;
        })).isInstanceOf(LedgerException.class);
        assertThat(repository.isReversed(tenant, original.id())).isFalse();
    }

    @Test
    void the_connection_must_belong_to_the_transactions_tenant() {
        UUID other = DB.createTenant();
        LedgerTransaction txn = sale(100);
        assertThatThrownBy(() -> new TenantScope(DB.appDataSource()).call(other, c -> {
            repository.appendWithin(c, txn);
            return null;
        })).isInstanceOf(RuntimeException.class);
        assertThat(repository.findTransaction(tenant, txn.id())).isEmpty();
    }
}
