package dev.chatledger.ledger;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke test for ledger-core: verifies that the balance invariant is enforced
 * and that a valid transaction can be constructed.
 *
 * <p>Requirement: 4 (Double-entry ledger).
 */
class LedgerTransactionSmokeTest {

    @Test
    void balanced_transaction_is_accepted() {
        UUID txnId    = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID arId     = UUID.randomUUID();
        UUID revId    = UUID.randomUUID();

        var debit  = new LedgerEntry(UUID.randomUUID(), txnId, arId,  1000L, 0L);
        var credit = new LedgerEntry(UUID.randomUUID(), txnId, revId, 0L, 1000L);

        var txn = new LedgerTransaction(
                txnId, tenantId, "ORDER_ACCEPTED", "ORDER",
                UUID.randomUUID(), List.of(debit, credit), Instant.now());

        assertThat(txn.entries()).hasSize(2);
    }

    @Test
    void unbalanced_transaction_is_rejected() {
        UUID txnId    = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID arId     = UUID.randomUUID();

        var debit = new LedgerEntry(UUID.randomUUID(), txnId, arId, 1000L, 0L);

        assertThatThrownBy(() -> new LedgerTransaction(
                txnId, tenantId, "ORDER_ACCEPTED", "ORDER",
                UUID.randomUUID(), List.of(debit), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unbalanced");
    }
}
