package dev.chatledger.orders;

import dev.chatledger.ingestion.OrderDecider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant A cannot read tenant B through any of the order repositories, and cannot write a row for B. These are the
 * contract tests named in {@code IsolationRegistry.REPOSITORY_CONTRACT_TESTS}; each has a positive control so an empty
 * result means something.
 *
 * <p>Requirement: 9.1. Task 4.
 */
class OrderRepositoriesIsolationIT {

    private final IngestionFixture f = new IngestionFixture();

    @Test
    void another_tenants_orders_cannot_be_read_or_written() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        String idB = IngestionFixture.id();
        f.ingest(b, idB, "Name: Bea\nPhone: +999 000 000 4321\n2 x rice @ 500");
        String idA = IngestionFixture.id();
        f.ingest(a, idA, "1 x beans @ 700");

        // Controls: each tenant reads its own order and its lines.
        OrderRepository.Stored ownB = f.orders.findByExternalId(b, idB).orElseThrow();
        assertThat(f.orders.lines(b, ownB.id())).hasSize(1);
        assertThat(f.orders.findByExternalId(a, idA)).isPresent();

        // A asks for B's order by its exact external id, and for B's lines by id.
        assertThat(f.orders.findByExternalId(a, idB)).isEmpty();
        assertThat(f.orders.lines(a, ownB.id())).isEmpty();
        assertThat(f.orders.count(a)).isEqualTo(1);
        assertThat(f.orders.count(b)).isEqualTo(1);

        // A cannot write a row that names B: the explicit tenant on the row and row-level security both refuse.
        OrderDecider.Draft draft = new OrderDecider.Draft(OrderDecider.Status.UNPARSED, null, "XAF", List.of(), null);
        OrderRepository.NewOrder forged = new OrderRepository.NewOrder(IngestionFixture.id(), null, "x", draft, null, null, null, null, null);
        try (var c = IngestionFixture.DB.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var set = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)");
                 var insert = c.prepareStatement(
                         "INSERT INTO orders(id, tenant_id, external_id, total_minor, currency, status, raw_text) "
                                 + "VALUES (?, ?, ?, NULL, 'XAF', 'UNPARSED', 'x')")) {
                set.setString(1, a.toString());
                set.execute();
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, b);
                insert.setString(3, forged.externalId());
                org.assertj.core.api.Assertions.assertThatThrownBy(insert::executeUpdate)
                        .isInstanceOf(java.sql.SQLException.class);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(f.orders.count(b)).isEqualTo(1);
    }

    @Test
    void another_tenants_catalog_cannot_be_read() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        f.seedCatalog(b, "rice", 1500);
        f.seedCatalog(a, "beans", 700);
        String keyRice = dev.chatledger.extraction.TextKeys.key("rice");
        String keyBeans = dev.chatledger.extraction.TextKeys.key("beans");
        assertThat(f.catalog.prices(b, Set.of(keyRice))).containsEntry(keyRice, 1500L);
        assertThat(f.catalog.prices(a, Set.of(keyBeans))).containsEntry(keyBeans, 700L);
        assertThat(f.catalog.prices(a, Set.of(keyRice, keyBeans))).containsOnlyKeys(keyBeans);
        assertThat(f.catalog.prices(b, Set.of(keyRice, keyBeans))).containsOnlyKeys(keyRice);
        assertThat(f.catalog.count(a)).isEqualTo(1);
        assertThat(f.catalog.prices(a, Set.of())).isEmpty();
    }

    @Test
    void catalog_lookup_is_equality_never_a_pattern() {
        UUID tenant = f.tenant();
        f.seedCatalog(tenant, "rice", 1500);
        f.seedCatalog(tenant, "100% cotton", 2000);
        String rice = dev.chatledger.extraction.TextKeys.key("rice");
        String cotton = dev.chatledger.extraction.TextKeys.key("100% cotton");
        assertThat(f.catalog.prices(tenant, Set.of(rice, cotton))).containsOnlyKeys(rice, cotton);   // control
        for (String wildcard : new String[]{"r%", "%", "%ice", "ri_e", "_ice", "r_%", "ric", "rice ", "100%", "100_ cotton", "rice\\"}) {
            assertThat(f.catalog.prices(tenant, Set.of(wildcard))).as("'" + wildcard + "' is a name, not a pattern").isEmpty();
        }
        assertThat(f.catalog.prices(tenant, Set.of("r%", rice))).containsOnlyKeys(rice);
    }

    @Test
    void another_tenants_customers_cannot_be_read() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        String idB = IngestionFixture.id();
        f.ingest(b, idB, "Name: Bea\nPhone: +999 000 000 4321\n1 x rice @ 5");
        f.ingest(a, IngestionFixture.id(), "Name: Ana\n1 x rice @ 5");
        UUID customerB = f.orders.findByExternalId(b, idB).orElseThrow().customerId();
        String hashB = f.phoneKeys.hash(b, dev.chatledger.extraction.Phone.of("+999 000 000 4321").orElseThrow());

        assertThat(f.customers.find(b, customerB)).isPresent();
        assertThat(f.customers.findByPhoneHash(b, hashB)).isPresent();
        assertThat(f.customers.find(a, customerB)).isEmpty();
        assertThat(f.customers.findByPhoneHash(a, hashB)).isEmpty();
        assertThat(f.customers.count(a)).isEqualTo(1);
        assertThat(f.customers.count(b)).isEqualTo(1);
    }

    @Test
    void another_tenants_row_cannot_be_read() {
        UUID a = f.tenant("XAF", 0);
        UUID b = f.tenant("EUR", 2);
        assertThat(f.tenants.money(a)).contains(new TenantMoney("XAF", 0));
        assertThat(f.tenants.money(b)).contains(new TenantMoney("EUR", 2));
        assertThat(f.tenants.money(UUID.randomUUID())).as("an unknown tenant").isEmpty();
    }
}
