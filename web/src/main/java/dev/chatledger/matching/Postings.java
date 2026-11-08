package dev.chatledger.matching;

import dev.chatledger.ledger.LedgerEntry;
import dev.chatledger.ledger.LedgerTransaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The two transactions matching posts, built in one place so that every path (the order's ingestion, the matcher, the tests) makes
 * the same entries. Amounts are minor units; every entry is greater than zero and debits equal credits.
 *
 * <ul>
 *   <li>{@code ORDER_ACCEPTED}, once per order: debit Accounts receivable, credit Sales revenue, the order total.</li>
 *   <li>{@code PAYMENT_MATCHED}, once per match: debit Mobile money clearing the whole payment; credit Accounts receivable what is
 *       applied to the order; credit Customer credit the excess, if any.</li>
 * </ul>
 */
public final class Postings {

    public static final String ORDER_ACCEPTED = "ORDER_ACCEPTED";
    public static final String PAYMENT_MATCHED = "PAYMENT_MATCHED";

    private Postings() {
    }

    public static LedgerTransaction orderAccepted(UUID tenantId, UUID orderId, long totalMinor, ChartOfAccounts.Accounts accounts, Instant at) {
        return new LedgerTransaction(UUID.randomUUID(), tenantId, ORDER_ACCEPTED, "ORDER", orderId, null, at,
                List.of(LedgerEntry.debit(accounts.receivable(), totalMinor), LedgerEntry.credit(accounts.revenue(), totalMinor)));
    }

    public static LedgerTransaction paymentMatched(UUID tenantId, UUID matchId, long appliedMinor, long excessMinor,
                                                   ChartOfAccounts.Accounts accounts, Instant at) {
        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(LedgerEntry.debit(accounts.clearing(), appliedMinor + excessMinor));
        entries.add(LedgerEntry.credit(accounts.receivable(), appliedMinor));
        if (excessMinor > 0) {
            entries.add(LedgerEntry.credit(accounts.customerCredit(), excessMinor));
        }
        return new LedgerTransaction(UUID.randomUUID(), tenantId, PAYMENT_MATCHED, "MATCH", matchId, null, at, entries);
    }
}
