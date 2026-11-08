package dev.chatledger.matching;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tenant A cannot read tenant B through the match repository and cannot write a match that names B. The first test is the contract test
 * named in {@code IsolationRegistry.REPOSITORY_CONTRACT_TESTS}; the second runs the repository as the schema owner, which row-level
 * security does not bind, so only each statement's own {@code tenant_id} predicate scopes the result.
 *
 * <p>Requirement: 9.1. Task 6.1.
 */
class MatchRepositoryIsolationIT {

    /** Tenant B has an active match, a pair the seller rejected, and open candidates; tenant A has one suggestion. */
    private static final class Two {
        final MatchWorld a = new MatchWorld();
        final MatchWorld b = new MatchWorld();
        final UUID orderA = a.order(5000, "PENDING_PAYMENT", "A-1", null);
        final UUID paymentA = a.payment(5000, "RECEIVED");
        final UUID suggestionA = a.suggested(orderA, paymentA);
        final UUID orderB = b.order(5000, "PAID", "B-1", null);
        final UUID paymentB = b.payment(5000, "MATCHED");
        final UUID activeB = b.activeMatch(orderB, paymentB, "AUTO", 5000, 0);
        final UUID openOrderB = b.order(7000, "PENDING_PAYMENT", "B-2", null);
        final UUID openPaymentB = b.payment(7000, "RECEIVED");
        final UUID rejectedB = b.rejected(openOrderB, openPaymentB, true);
    }

    private static void assertScopedTo(MatchRepository repository, Two t) {
        // Controls: each tenant reads its own rows.
        assertThat(repository.find(t.b.tenant, t.activeB)).isPresent();
        assertThat(repository.forPayment(t.b.tenant, t.paymentB)).hasSize(1);
        assertThat(repository.forOrder(t.b.tenant, t.orderB)).hasSize(1);
        assertThat(repository.appliedMinor(t.b.tenant, t.orderB)).isEqualTo(5000);
        assertThat(repository.wasRejected(t.b.tenant, t.openOrderB, t.openPaymentB)).isTrue();
        assertThat(repository.count(t.b.tenant)).isEqualTo(2);
        assertThat(repository.openOrders(t.b.tenant, "XAF", 100)).extracting(o -> o.id()).containsExactly(t.openOrderB);
        assertThat(repository.openPayments(t.b.tenant, "XAF", 100)).extracting(p -> p.id()).containsExactly(t.openPaymentB);
        assertThat(repository.find(t.a.tenant, t.suggestionA)).isPresent();
        assertThat(repository.count(t.a.tenant)).isEqualTo(1);

        // Tenant A asks for B's rows by their exact ids.
        assertThat(repository.find(t.a.tenant, t.activeB)).isEmpty();
        assertThat(repository.forPayment(t.a.tenant, t.paymentB)).isEmpty();
        assertThat(repository.forOrder(t.a.tenant, t.orderB)).isEmpty();
        assertThat(repository.appliedMinor(t.a.tenant, t.orderB)).isZero();
        assertThat(repository.wasRejected(t.a.tenant, t.openOrderB, t.openPaymentB)).isFalse();
        assertThat(repository.openOrders(t.a.tenant, "XAF", 100)).extracting(o -> o.id()).containsExactly(t.orderA);
        assertThat(repository.openPayments(t.a.tenant, "XAF", 100)).extracting(p -> p.id()).containsExactly(t.paymentA);
    }

    @Test
    void another_tenants_matches_cannot_be_read_or_written() {
        Two t = new Two();
        MatchRepository repository = new MatchRepository(MatchWorld.DB.appDataSource());
        assertScopedTo(repository, t);

        // A cannot link its suggestion to B's order or payment: the composite foreign keys refuse a pair that crosses tenants.
        assertThatThrownBy(() -> repository.insertSuggestion(t.a.tenant, t.orderB, t.paymentA, 70, "[]")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.insertSuggestion(t.a.tenant, t.orderA, t.paymentB, 70, "[]")).isInstanceOf(IllegalStateException.class);

        // A cannot write a row that names B: the explicit tenant on the row and row-level security both refuse.
        try (Connection c = MatchWorld.DB.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var set = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)");
                 var insert = c.prepareStatement(
                         "INSERT INTO match(id, tenant_id, order_id, payment_id, score, reasons_json, state) VALUES (?, ?, ?, ?, 70, '[]', 'SUGGESTED')")) {
                set.setString(1, t.a.tenant.toString());
                set.execute();
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, t.b.tenant);
                insert.setObject(3, t.openOrderB);
                insert.setObject(4, t.openPaymentB);
                assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(repository.count(t.b.tenant)).isEqualTo(2);
        assertThat(repository.count(t.a.tenant)).isEqualTo(1);
    }

    @Test
    void the_statements_scope_by_tenant_by_themselves_even_where_row_level_security_does_not_apply() throws SQLException {
        Two t = new Two();
        MatchRepository asOwner = new MatchRepository(MatchWorld.DB.migrateDataSource());
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection(); var s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM match WHERE tenant_id IN ('" + t.a.tenant + "', '" + t.b.tenant + "')")) {
            rs.next();
            assertThat(rs.getLong(1)).as("the owner sees both tenants' matches with no tenant setting").isEqualTo(3);
        }
        assertScopedTo(asOwner, t);
    }

    @Test
    void an_unknown_tenant_sees_nothing() {
        MatchRepository repository = new MatchRepository(MatchWorld.DB.appDataSource());
        UUID nobody = UUID.randomUUID();
        assertThat(repository.count(nobody)).isZero();
        assertThat(repository.openOrders(nobody, "XAF", 10)).isEmpty();
        assertThat(repository.openPayments(nobody, "XAF", 10)).isEmpty();
    }
}
