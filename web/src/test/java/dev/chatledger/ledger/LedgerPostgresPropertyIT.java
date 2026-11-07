package dev.chatledger.ledger;

import dev.chatledger.ledger.jdbc.JdbcLedgerRepository;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;
import java.util.UUID;

/**
 * The same random sequences of postings and reversals as the in-memory property
 * (same generators and same scenario runner from the ledger-core test-jar), run
 * against PostgreSQL as the application role.
 *
 * <p>Checked after every step: each committed transaction is balanced, the net
 * balances of the tenant sum to zero, balances equal an independent BigInteger
 * oracle (so nothing overflowed silently), a reversal undoes its original, and
 * reversing twice is refused. Amounts include 1 and values near Long.MAX_VALUE.
 *
 * <p>Fewer tries than the in-memory property, because every step is a round trip
 * to a real database. Requirement 4. Task 2.2.
 */
class LedgerPostgresPropertyIT {

    private static final LedgerDatabase DB = LedgerDatabase.get();

    @Property(tries = 40)
    void random_sequences_of_postings_and_reversals_keep_every_invariant_in_postgres(
            @ForAll("operations") List<LedgerScenario.Op> ops) {
        UUID tenant = DB.createTenant();
        var repository = new JdbcLedgerRepository(DB.appDataSource());
        var accounts = LedgerScenario.createAccounts(repository, tenant);

        new LedgerScenario(new LedgerService(repository), repository, tenant, accounts).run(ops);
    }

    @Provide
    Arbitrary<List<LedgerScenario.Op>> operations() {
        return LedgerGenerators.operations(4, 12);
    }
}
