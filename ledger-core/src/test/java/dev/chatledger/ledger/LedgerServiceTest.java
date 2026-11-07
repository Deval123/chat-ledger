package dev.chatledger.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 4: posting, reversal and balances on the in-memory repository. */
class LedgerServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private final InMemoryLedgerRepository repository = new InMemoryLedgerRepository();
    private final LedgerService service = new LedgerService(repository);
    private final UUID tenant = UUID.randomUUID();
    private Account receivable;
    private Account revenue;

    @BeforeEach
    void accounts() {
        receivable = new Account(UUID.randomUUID(), tenant, "AR", "Accounts receivable", AccountType.ASSET);
        revenue = new Account(UUID.randomUUID(), tenant, "REV", "Sales revenue", AccountType.REVENUE);
        repository.createAccount(receivable);
        repository.createAccount(revenue);
    }

    private LedgerTransaction sale(long amount) {
        return new LedgerTransaction(UUID.randomUUID(), tenant, "ORDER_ACCEPTED", "ORDER", UUID.randomUUID(),
                null, NOW, List.of(LedgerEntry.debit(receivable.id(), amount), LedgerEntry.credit(revenue.id(), amount)));
    }

    @Test
    void posting_updates_balances_signed_by_account_type() {
        service.post(sale(5000));

        var balances = service.balances(tenant);
        assertThat(balances).filteredOn(b -> b.accountId().equals(receivable.id()))
                .singleElement().satisfies(b -> {
                    assertThat(b.netDebitMinor()).isEqualTo(5000);
                    assertThat(b.signedMinor()).isEqualTo(5000);   // asset: debit-normal
                });
        assertThat(balances).filteredOn(b -> b.accountId().equals(revenue.id()))
                .singleElement().satisfies(b -> {
                    assertThat(b.netDebitMinor()).isEqualTo(-5000);
                    assertThat(b.signedMinor()).isEqualTo(5000);   // revenue: credit-normal
                });
        assertThat(service.trialBalance(tenant)).isEqualTo(BigInteger.ZERO);
    }

    @Test
    void reversal_restores_every_balance_and_keeps_the_original() {
        var original = sale(5000);
        service.post(original);

        var reversal = service.reverse(tenant, original.id(), UUID.randomUUID(), NOW);

        assertThat(service.balances(tenant)).allSatisfy(b -> assertThat(b.netDebitMinor()).isZero());
        assertThat(repository.findTransaction(tenant, original.id())).contains(original);
        assertThat(repository.findTransaction(tenant, reversal.id())).contains(reversal);
    }

    @Test
    void a_second_reversal_is_rejected_and_changes_nothing() {
        var original = sale(5000);
        service.post(original);
        service.reverse(tenant, original.id(), UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> service.reverse(tenant, original.id(), UUID.randomUUID(), NOW))
                .isInstanceOf(AlreadyReversedException.class);
        assertThat(service.balances(tenant)).allSatisfy(b -> assertThat(b.netDebitMinor()).isZero());
    }

    @Test
    void a_second_reversal_is_reported_as_such_even_when_it_would_overflow() {
        var original = new LedgerTransaction(UUID.randomUUID(), tenant, "X", "X", UUID.randomUUID(), null, NOW,
                List.of(LedgerEntry.debit(receivable.id(), Long.MAX_VALUE), LedgerEntry.credit(revenue.id(), Long.MAX_VALUE)));
        service.post(original);
        service.reverse(tenant, original.id(), UUID.randomUUID(), NOW);
        // Push AR to -MAX so a second reversal (AR -MAX again) would overflow.
        service.post(new LedgerTransaction(UUID.randomUUID(), tenant, "X", "X", UUID.randomUUID(), null, NOW,
                List.of(LedgerEntry.debit(revenue.id(), Long.MAX_VALUE), LedgerEntry.credit(receivable.id(), Long.MAX_VALUE))));

        assertThatThrownBy(() -> service.reverse(tenant, original.id(), UUID.randomUUID(), NOW))
                .isInstanceOf(AlreadyReversedException.class);
    }

    @Test
    void an_entry_on_another_tenants_account_is_rejected_before_anything_is_written() {
        var other = new Account(UUID.randomUUID(), UUID.randomUUID(), "AR", "Other tenant", AccountType.ASSET);
        repository.createAccount(other);
        var crossTenant = new LedgerTransaction(UUID.randomUUID(), tenant, "X", "X", UUID.randomUUID(), null, NOW,
                List.of(LedgerEntry.debit(other.id(), 10), LedgerEntry.credit(revenue.id(), 10)));

        assertThatThrownBy(() -> service.post(crossTenant)).isInstanceOf(LedgerException.class);
        assertThat(repository.findTransaction(tenant, crossTenant.id())).isEmpty();
        assertThat(service.balances(tenant)).allSatisfy(b -> assertThat(b.netDebitMinor()).isZero());
    }

    @Test
    void another_tenant_cannot_see_or_reverse_the_transaction() {
        var original = sale(5000);
        service.post(original);
        var otherTenant = UUID.randomUUID();

        assertThat(repository.findTransaction(otherTenant, original.id())).isEmpty();
        assertThat(service.balances(otherTenant)).isEmpty();
        assertThatThrownBy(() -> service.reverse(otherTenant, original.id(), UUID.randomUUID(), NOW))
                .isInstanceOf(LedgerException.class).hasMessageContaining("not found");
    }

    @Test
    void a_posting_that_would_overflow_a_balance_is_rejected() {
        service.post(new LedgerTransaction(UUID.randomUUID(), tenant, "X", "X", UUID.randomUUID(), null, NOW,
                List.of(LedgerEntry.debit(receivable.id(), Long.MAX_VALUE), LedgerEntry.credit(revenue.id(), Long.MAX_VALUE))));

        assertThatThrownBy(() -> service.post(sale(1)))
                .isInstanceOf(LedgerException.class).hasMessageContaining("overflow");
        assertThat(repository.netDebit(tenant, receivable.id())).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void a_reversal_cannot_be_posted_directly() {
        var reversal = sale(1).reverse(UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> service.post(reversal))
                .isInstanceOf(LedgerException.class).hasMessageContaining("reverse()");
    }
}
