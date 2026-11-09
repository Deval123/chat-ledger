package dev.chatledger.matching;

import dev.chatledger.flags.FlagRepository;
import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.tenancy.TenantScope;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static dev.chatledger.matching.BoardRepositoryIsolationIT.flagOrder;
import static dev.chatledger.matching.BoardRepositoryIsolationIT.flagPayment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tenant A cannot read, resolve or write tenant B's flags through {@link FlagRepository}. The first test is the contract test named in
 * {@code IsolationRegistry.REPOSITORY_CONTRACT_TESTS}; the second runs the same statements as the schema owner (row-level security does not
 * bind it), so only each statement's own tenant predicate and the composite foreign keys scope it. Every refusal has a positive control.
 *
 * <p>Requirement: 9.1. Task 7.
 */
class FlagRepositoryIsolationIT {
    private final FlagRepository flags = new FlagRepository();

    private static final class Two {
        final MatchWorld a = new MatchWorld();
        final MatchWorld b = new MatchWorld();
        final UUID orderB = b.order(5000, "PENDING_PAYMENT", "B-1", null);
        final UUID paymentB = b.payment(5000, "RECEIVED", true, null, "Bea Nkomo", "dd".repeat(32), "4321", "XAF", Instant.now().minusSeconds(60));
        final UUID orderA = a.order(5000, "PENDING_PAYMENT", "A-1", null);
        final UUID paymentA = a.payment(5000, "RECEIVED");
        final UUID flagB;

        Two() {
            flagPayment(b, paymentB, "SENDER_BURST", null, false);
            flagOrder(b, orderB, false);
            flagPayment(b, paymentB, "PHONE_MISMATCH", orderB, false);
            flagB = UUID.fromString(MatchingFixture.rows("SELECT id::text FROM flag WHERE tenant_id = ? AND code = 'SENDER_BURST'", b.tenant).get(0));
        }
    }

    private interface Work {
        void run(Connection c) throws SQLException;
    }

    private static void as(javax.sql.DataSource ds, UUID tenant, Work work) throws SQLException {
        new TenantScope(ds).call(tenant, c -> {
            work.run(c);
            return null;
        });
    }

    private void assertScoped(javax.sql.DataSource ds, Two t) throws SQLException {
        // Controls: B sees its own flags, A its own (none).
        as(ds, t.b.tenant, c -> {
            assertThat(flags.stateWithin(c, t.b.tenant, t.paymentB, t.orderB)).isEqualTo(new FlagRepository.FlagState(true, true, true));
            assertThat(flags.lockWithin(c, t.b.tenant, t.flagB)).isPresent();
            assertThat(flags.senderRecordsWithin(c, t.b.tenant, "dd".repeat(32), UUID.randomUUID(), Instant.now())).hasSize(1);
        });
        // A asks for B's ids and B's phone hash.
        as(ds, t.a.tenant, c -> {
            assertThat(flags.stateWithin(c, t.a.tenant, t.paymentB, t.orderB)).isEqualTo(new FlagRepository.FlagState(false, false, false));
            assertThat(flags.lockWithin(c, t.a.tenant, t.flagB)).isEmpty();
            assertThat(flags.resolveWithin(c, t.a.tenant, t.flagB, t.a.user, Instant.now())).isZero();
            assertThat(flags.senderRecordsWithin(c, t.a.tenant, "dd".repeat(32), UUID.randomUUID(), Instant.now())).isEmpty();
            assertThat(flags.stateWithin(c, t.a.tenant, t.paymentA, t.orderA)).isEqualTo(new FlagRepository.FlagState(false, false, false));
        });
        assertThat(MatchingFixture.rows("SELECT (resolved_at IS NULL)::text FROM flag WHERE tenant_id = ? AND code = 'SENDER_BURST'", t.b.tenant))
                .as("B's flag is still open").containsExactly("true");
    }

    @Test
    void another_tenants_flags_cannot_be_read_or_written() throws SQLException {
        Two t = new Two();
        assertScoped(MatchWorld.DB.appDataSource(), t);
        // A cannot write a flag about B's payment: row-level security on the tenant, and the composite foreign key on the subject.
        assertThatThrownBy(() -> as(MatchWorld.DB.appDataSource(), t.a.tenant,
                c -> flags.insertWithin(c, t.a.tenant, Finding.payment(FlagCode.SENDER_BURST, t.paymentB), Instant.now()))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> as(MatchWorld.DB.appDataSource(), t.a.tenant,
                c -> flags.insertWithin(c, t.b.tenant, Finding.payment(FlagCode.DUPLICATE_TX, t.paymentB), Instant.now()))).isInstanceOf(SQLException.class);
        // Control: A can flag its own payment.
        as(MatchWorld.DB.appDataSource(), t.a.tenant, c -> assertThat(flags.insertWithin(c, t.a.tenant,
                Finding.payment(FlagCode.SENDER_BURST, t.paymentA), Instant.now())).isTrue());
    }

    @Test
    void the_statements_scope_by_tenant_by_themselves_even_where_row_level_security_does_not_apply() throws SQLException {
        Two t = new Two();
        assertScoped(MatchWorld.DB.migrateDataSource(), t);
        // As the owner the foreign key still refuses a flag that names A but points at B's payment.
        assertThatThrownBy(() -> as(MatchWorld.DB.migrateDataSource(), t.a.tenant,
                c -> flags.insertWithin(c, t.a.tenant, Finding.pair(FlagCode.PHONE_MISMATCH, t.paymentA, t.orderB), Instant.now())))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_flag_order_same_tenant");
    }
}
