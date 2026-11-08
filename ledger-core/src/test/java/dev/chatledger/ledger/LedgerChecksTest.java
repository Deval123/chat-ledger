package dev.chatledger.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checks made before any posting, shared by {@link LedgerService} and by repositories that append on the caller's own connection: an
 * account must be the tenant's, and no balance may leave the range of a long.
 *
 * <p>Requirement: 4.1, 4.3, 4.4. Task 6.1.
 */
class LedgerChecksTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private final InMemoryLedgerRepository repository = new InMemoryLedgerRepository();
    private final LedgerService service = new LedgerService(repository);
    private UUID tenant;
    private Account debitSide;
    private Account creditSide;

    @BeforeEach
    void accounts() {
        tenant = UUID.randomUUID();
        debitSide = account(tenant, "D");
        creditSide = account(tenant, "C");
    }

    private Account account(UUID owner, String code) {
        Account a = new Account(UUID.randomUUID(), owner, code, code, AccountType.ASSET);
        repository.createAccount(a);
        return a;
    }

    private LedgerTransaction txn(UUID owner, Account debit, Account credit, long amount) {
        return new LedgerTransaction(UUID.randomUUID(), owner, "K", "R", UUID.randomUUID(), null, NOW,
                List.of(LedgerEntry.debit(debit.id(), amount), LedgerEntry.credit(credit.id(), amount)));
    }

    @Test
    void a_posting_between_the_tenants_own_accounts_passes() {
        assertThatCode(() -> LedgerChecks.verify(txn(tenant, debitSide, creditSide, 100), repository)).doesNotThrowAnyException();
    }

    @Test
    void an_unknown_account_and_another_tenants_account_are_refused() {
        Account foreign = account(UUID.randomUUID(), "F");
        assertThatThrownBy(() -> LedgerChecks.verify(txn(tenant, debitSide, foreign, 100), repository))
                .isInstanceOf(LedgerException.class).hasMessageContaining("does not belong to tenant");
        Account unknown = new Account(UUID.randomUUID(), tenant, "U", "U", AccountType.ASSET);
        assertThatThrownBy(() -> LedgerChecks.verify(txn(tenant, unknown, creditSide, 100), repository))
                .isInstanceOf(LedgerException.class).hasMessageContaining("does not belong to tenant");
    }

    @Test
    void a_balance_may_reach_the_largest_long_but_not_pass_it() {
        service.post(txn(tenant, debitSide, creditSide, Long.MAX_VALUE - 10));
        assertThatCode(() -> LedgerChecks.verify(txn(tenant, debitSide, creditSide, 10), repository)).doesNotThrowAnyException();
        for (long over : new long[]{11, 12, 100, Long.MAX_VALUE}) {
            assertThatThrownBy(() -> LedgerChecks.verify(txn(tenant, debitSide, creditSide, over), repository)).as("by " + over)
                    .isInstanceOf(LedgerException.class).hasMessageContaining("overflow");
        }
    }

    @Test
    void a_balance_may_not_reach_the_smallest_long() {
        service.post(txn(tenant, debitSide, creditSide, Long.MAX_VALUE));   // the credit side is now at -MAX
        assertThatThrownBy(() -> LedgerChecks.verify(txn(tenant, debitSide, creditSide, 1), repository))
                .isInstanceOf(LedgerException.class).hasMessageContaining("overflow");
        assertThat(repository.netDebit(tenant, creditSide.id())).isEqualTo(-Long.MAX_VALUE);
    }

    @Test
    void the_service_applies_the_same_checks_before_it_posts() {
        Account foreign = account(UUID.randomUUID(), "F");
        assertThatThrownBy(() -> service.post(txn(tenant, debitSide, foreign, 100))).isInstanceOf(LedgerException.class);
        assertThat(repository.netDebit(tenant, debitSide.id())).isZero();
    }
}
