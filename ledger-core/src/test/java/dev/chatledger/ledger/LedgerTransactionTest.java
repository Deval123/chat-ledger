package dev.chatledger.ledger;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 4.1, 4.2, 4.4: balanced entries, no floating point, corrections by reversal. */
class LedgerTransactionTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID AR = UUID.randomUUID();
    private static final UUID REV = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private static LedgerTransaction txn(List<LedgerEntry> entries) {
        return new LedgerTransaction(UUID.randomUUID(), TENANT, "ORDER_ACCEPTED", "ORDER",
                UUID.randomUUID(), null, NOW, entries);
    }

    @Test
    void balanced_transaction_is_accepted() {
        var t = txn(List.of(LedgerEntry.debit(AR, 5000), LedgerEntry.credit(REV, 5000)));
        assertThat(t.debitTotalMinor()).isEqualTo(5000);
        assertThat(t.creditTotalMinor()).isEqualTo(5000);
    }

    @Test
    void unbalanced_transaction_is_rejected() {
        assertThatThrownBy(() -> txn(List.of(LedgerEntry.debit(AR, 5000), LedgerEntry.credit(REV, 4999))))
                .isInstanceOf(LedgerException.class).hasMessageContaining("Unbalanced");
    }

    @Test
    void fewer_than_two_entries_is_rejected() {
        assertThatThrownBy(() -> txn(List.of(LedgerEntry.debit(AR, 1))))
                .isInstanceOf(LedgerException.class).hasMessageContaining("at least two");
        assertThatThrownBy(() -> txn(List.of()))
                .isInstanceOf(LedgerException.class).hasMessageContaining("at least two");
    }

    @Test
    void zero_and_negative_amounts_are_rejected() {
        assertThatThrownBy(() -> LedgerEntry.debit(AR, 0)).isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> LedgerEntry.credit(AR, -1)).isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> LedgerEntry.debit(AR, Long.MIN_VALUE)).isInstanceOf(LedgerException.class);
    }

    @Test
    void a_side_total_that_overflows_is_rejected_not_wrapped() {
        // MAX + 1 wraps to Long.MIN_VALUE; a wrapped debit total could "balance" a negative credit.
        assertThatThrownBy(() -> txn(List.of(
                LedgerEntry.debit(AR, Long.MAX_VALUE), LedgerEntry.debit(AR, 1),
                LedgerEntry.credit(REV, Long.MAX_VALUE))))
                .isInstanceOf(LedgerException.class).hasMessageContaining("overflows");
    }

    @Test
    void largest_possible_transaction_is_accepted() {
        var t = txn(List.of(LedgerEntry.debit(AR, Long.MAX_VALUE), LedgerEntry.credit(REV, Long.MAX_VALUE)));
        assertThat(t.netDebitByAccount()).containsEntry(AR, Long.MAX_VALUE).containsEntry(REV, -Long.MAX_VALUE);
    }

    @Test
    void net_effect_on_one_account_with_both_sides_cancels() {
        var t = txn(List.of(LedgerEntry.debit(AR, 7), LedgerEntry.credit(AR, 7)));
        assertThat(t.netDebitByAccount()).containsEntry(AR, 0L);
    }

    @Test
    void reversal_swaps_every_side_and_links_to_the_original() {
        var original = txn(List.of(LedgerEntry.debit(AR, 5000), LedgerEntry.credit(REV, 5000)));
        var reversalId = UUID.randomUUID();

        var reversal = original.reverse(reversalId, NOW.plusSeconds(60));

        assertThat(reversal.id()).isEqualTo(reversalId);
        assertThat(reversal.reversesTxnId()).isEqualTo(original.id());
        assertThat(reversal.tenantId()).isEqualTo(original.tenantId());
        assertThat(reversal.kind()).isEqualTo(LedgerTransaction.REVERSAL_KIND);
        assertThat(reversal.entries()).extracting(LedgerEntry::accountId, LedgerEntry::side, LedgerEntry::amountMinor)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(AR, Side.CREDIT, 5000L),
                        org.assertj.core.groups.Tuple.tuple(REV, Side.DEBIT, 5000L));
        // The original is a record: it cannot have changed.
        assertThat(original.entries()).extracting(LedgerEntry::side).containsExactly(Side.DEBIT, Side.CREDIT);
    }

    @Test
    void a_reversal_cannot_be_reversed() {
        var reversal = txn(List.of(LedgerEntry.debit(AR, 1), LedgerEntry.credit(REV, 1)))
                .reverse(UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> reversal.reverse(UUID.randomUUID(), NOW))
                .isInstanceOf(LedgerException.class).hasMessageContaining("cannot be reversed");
    }

    @Test
    void a_transaction_cannot_reverse_itself() {
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> new LedgerTransaction(id, TENANT, "X", "X", UUID.randomUUID(), id, NOW,
                List.of(LedgerEntry.debit(AR, 1), LedgerEntry.credit(REV, 1))))
                .isInstanceOf(LedgerException.class);
    }

    @Test
    void entries_are_copied_so_the_transaction_stays_immutable() {
        var list = new java.util.ArrayList<>(List.of(LedgerEntry.debit(AR, 1), LedgerEntry.credit(REV, 1)));
        var t = txn(list);
        list.clear();
        assertThat(t.entries()).hasSize(2);
        assertThatThrownBy(() -> t.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
