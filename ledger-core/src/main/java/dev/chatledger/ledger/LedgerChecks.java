package dev.chatledger.ledger;

import java.util.Map;
import java.util.UUID;

/**
 * The checks made before any posting, whichever path posts it ({@link LedgerService} or a repository that appends on the caller's own
 * database connection, so that a posting commits together with a match and a status change): every account belongs to the tenant, and
 * no account balance would leave the range of a long. One definition, so the two paths cannot drift apart.
 */
public final class LedgerChecks {

    private LedgerChecks() {
    }

    /** @throws LedgerException if an account is not the tenant's or a balance would overflow */
    public static void verify(LedgerTransaction transaction, AccountLookup lookup) {
        UUID tenantId = transaction.tenantId();
        for (Map.Entry<UUID, Long> change : transaction.netDebitByAccount().entrySet()) {
            lookup.findAccount(tenantId, change.getKey())
                    .orElseThrow(() -> new LedgerException(
                            "Account " + change.getKey() + " does not belong to tenant " + tenantId));
            long current = lookup.netDebit(tenantId, change.getKey());
            long next;
            try {
                next = Math.addExact(current, change.getValue());
            } catch (ArithmeticException overflow) {
                throw new LedgerException(
                        "Posting would overflow the balance of account " + change.getKey(), overflow);
            }
            if (next == Long.MIN_VALUE) {
                throw new LedgerException(
                        "Posting would overflow the balance of account " + change.getKey());
            }
        }
    }
}
