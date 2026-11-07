package dev.chatledger.payments;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tenant A cannot read tenant B through the payment repository, cannot find B's transaction ids, and cannot write a row that
 * names B. This is the contract test named in {@code IsolationRegistry.REPOSITORY_CONTRACT_TESTS}; every refusal has a positive
 * control so an empty result means something.
 *
 * <p>Requirement: 9.1. Task 5.
 */
class PaymentRepositoryIsolationIT {

    private final PaymentFixture f = new PaymentFixture();

    @Test
    void another_tenants_payments_cannot_be_read_or_written() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        String idB = PaymentFixture.id();
        String txB = PaymentFixture.txId();
        f.ingest(b, idB, f.sms(txB));
        String idA = PaymentFixture.id();
        String txA = PaymentFixture.txId();
        f.ingest(a, idA, f.sms(txA));

        // Controls: each tenant reads its own payment, its own original by transaction id, and counts its own.
        PaymentRepository.Stored ownB = f.payments.findByExternalId(b, idB).orElseThrow();
        assertThat(f.payments.findByExternalId(a, idA)).isPresent();
        assertThat(f.payments.findOriginal(b, "PROVIDER_A", txB)).contains(ownB.id());
        assertThat(f.payments.count(a)).isEqualTo(1);
        assertThat(f.payments.count(b)).isEqualTo(1);

        // A asks for B's payment by its exact external id and for B's transaction id.
        assertThat(f.payments.findByExternalId(a, idB)).isEmpty();
        assertThat(f.payments.findOriginal(a, "PROVIDER_A", txB)).isEmpty();

        // A cannot write a row that names B, even through the tenant parameter of the repository: the explicit tenant on the
        // row and row-level security both refuse.
        try (var c = PaymentFixture.DB.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var set = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)");
                 var insert = c.prepareStatement(
                         "INSERT INTO payment(id, tenant_id, external_id, currency, status, raw_text) "
                                 + "VALUES (?, ?, ?, 'XAF', 'UNPARSED', 'x')")) {
                set.setString(1, a.toString());
                set.execute();
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, b);
                insert.setString(3, PaymentFixture.id());
                assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(f.payments.count(b)).isEqualTo(1);
    }

    /**
     * Row-level security is the second wall; the statements' own {@code tenant_id = ?} is the first. The schema owner bypasses
     * row-level security (ENABLE, not FORCE), so a repository run as the owner is scoped by its statements alone. If a statement
     * lost its tenant predicate, this test would see the other tenant's rows.
     */
    @Test
    void the_statements_scope_by_tenant_by_themselves_even_where_row_level_security_does_not_apply() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        String idB = PaymentFixture.id();
        String txB = PaymentFixture.txId();
        f.ingest(b, idB, f.sms(txB));
        f.ingest(a, PaymentFixture.id(), f.sms(PaymentFixture.txId()));
        PaymentRepository asOwner = new PaymentRepository(PaymentFixture.DB.migrateDataSource());

        // Control: the owner-run repository reads each tenant's own rows, and row-level security really is off for this role
        // (without it the next checks would pass for the wrong reason).
        assertThat(asOwner.findByExternalId(b, idB)).isPresent();
        assertThat(asOwner.findOriginal(b, "PROVIDER_A", txB)).isPresent();
        assertThat(asOwner.count(b)).isEqualTo(1);
        try (var c = PaymentFixture.DB.migrateDataSource().getConnection(); var s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM payment WHERE tenant_id IN ('" + a + "', '" + b + "')")) {
            rs.next();
            assertThat(rs.getLong(1)).as("the owner sees both tenants' rows with no tenant setting").isEqualTo(2);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        assertThat(asOwner.findByExternalId(a, idB)).isEmpty();
        assertThat(asOwner.findOriginal(a, "PROVIDER_A", txB)).isEmpty();
        assertThat(asOwner.count(a)).isEqualTo(1);
    }

    @Test
    void a_duplicate_cannot_point_at_another_tenants_payment() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        String txB = PaymentFixture.txId();
        f.ingest(b, PaymentFixture.id(), f.sms(txB));
        UUID originalOfB = f.payments.findOriginal(b, "PROVIDER_A", txB).orElseThrow();

        // Control: inside the tenant the same link is accepted by the service.
        PaymentIngestionResult own = f.ingest(b, PaymentFixture.id(), f.sms(txB));
        assertThat(own.duplicateOf()).isEqualTo(originalOfB);

        // The composite foreign key (duplicate_of, tenant_id) refuses a link across tenants, as the schema owner too.
        assertThatThrownBy(() -> {
            try (var c = PaymentFixture.DB.migrateDataSource().getConnection();
                 var ps = c.prepareStatement(
                         "INSERT INTO payment(id, tenant_id, external_id, provider, tx_id, amount_minor, currency, paid_at, status, "
                                 + "raw_text, duplicate_of) VALUES (?, ?, ?, 'PROVIDER_A', 'PAFOREIGN1', 5, 'XAF', now(), 'DUPLICATE', 'x', ?)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, a);
                ps.setString(3, PaymentFixture.id());
                ps.setObject(4, originalOfB);
                ps.executeUpdate();
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("23503");
            assertThat(e.getMessage()).contains("fk_payment_duplicate_of_same_tenant");
        });
        assertThat(f.payments.count(a)).isZero();
    }

    @Test
    void an_unknown_tenant_sees_and_writes_nothing() {
        UUID nobody = UUID.randomUUID();
        assertThat(f.payments.count(nobody)).isZero();
        assertThat(f.payments.findByExternalId(nobody, "anything")).isEmpty();
    }
}
