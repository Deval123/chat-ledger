package dev.chatledger.orders;

import dev.chatledger.extraction.Phone;
import dev.chatledger.ingestion.OrderDecider;
import dev.chatledger.extraction.TextKeys;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Row-level security is the second wall; the statements' own {@code tenant_id = ?} is the first. The schema owner bypasses
 * row-level security (ENABLE, not FORCE), so a repository run as the owner is scoped by its statements alone. If any statement
 * lost its tenant predicate, these tests would see the other tenant's rows.
 *
 * <p>Requirement: 9.1. Tasks 4 and 5 (the order, customer and catalog repositories).
 */
class OrderRepositoriesStatementScopeIT {

    private final IngestionFixture f = new IngestionFixture();
    private final javax.sql.DataSource owner = IngestionFixture.DB.migrateDataSource();
    private final OrderRepository orders = new OrderRepository(owner);
    private final CustomerRepository customers = new CustomerRepository(owner);
    private final CatalogRepository catalog = new CatalogRepository(owner);

    private static void assertRlsDoesNotApplyToTheOwner(UUID a, UUID b) {
        try (var c = IngestionFixture.DB.migrateDataSource().getConnection(); var s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM orders WHERE tenant_id IN ('" + a + "', '" + b + "')")) {
            rs.next();
            assertThat(rs.getLong(1)).as("the owner sees both tenants' orders with no tenant setting").isEqualTo(2);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void order_statements_scope_by_tenant_by_themselves() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        String idA = IngestionFixture.id();
        String idB = IngestionFixture.id();
        f.ingest(a, idA, "Name: Ana\nPhone: +999 000 000 1234\n1 x beans @ 700");
        f.ingest(b, idB, "Name: Bea\nPhone: +999 000 000 4321\n2 x rice @ 500");
        assertRlsDoesNotApplyToTheOwner(a, b);

        // Controls: the owner-run repository reads each tenant's own data.
        OrderRepository.Stored ownB = orders.findByExternalId(b, idB).orElseThrow();
        assertThat(orders.lines(b, ownB.id())).hasSize(1);
        assertThat(orders.count(b)).isEqualTo(1);
        assertThat(orders.findByExternalId(a, idA)).isPresent();

        assertThat(orders.findByExternalId(a, idB)).isEmpty();
        assertThat(orders.lines(a, ownB.id())).isEmpty();
        assertThat(orders.count(a)).isEqualTo(1);
    }

    @Test
    void customer_statements_scope_by_tenant_by_themselves() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        String idB = IngestionFixture.id();
        f.ingest(a, IngestionFixture.id(), "Name: Ana\nPhone: +999 000 000 1234\n1 x beans @ 700");
        f.ingest(b, idB, "Name: Bea\nPhone: +999 000 000 4321\n2 x rice @ 500");
        assertRlsDoesNotApplyToTheOwner(a, b);
        UUID customerB = orders.findByExternalId(b, idB).orElseThrow().customerId();
        String hashB = f.phoneKeys.hash(b, Phone.of("+999 000 000 4321").orElseThrow());

        assertThat(customers.find(b, customerB)).isPresent();
        assertThat(customers.findByPhoneHash(b, hashB)).isPresent();
        assertThat(customers.count(b)).isEqualTo(1);

        assertThat(customers.find(a, customerB)).isEmpty();
        assertThat(customers.findByPhoneHash(a, hashB)).isEmpty();
        assertThat(customers.count(a)).isEqualTo(1);
    }

    @Test
    void the_customer_lookup_inside_an_order_insert_is_scoped_by_tenant_by_itself() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        // Real hashes differ per tenant, so two tenants never share one; the repository is handed the same hash for both on purpose, the
        // only way to see whether its "find the customer by phone hash" statement looks at the tenant. B's customer is created first.
        String sharedHash = "ab".repeat(32);
        OrderDecider.Draft draft = new OrderDecider.Draft(OrderDecider.Status.UNPARSED, null, "XAF", List.of(), null);
        String idB = IngestionFixture.id();
        String idA = IngestionFixture.id();
        assertThat(orders.insert(b, new OrderRepository.NewOrder(idB, null, "x", draft, "Bea", sharedHash, "4321", null, null), (c, o, cu) -> { })).isPresent();
        assertThat(orders.insert(a, new OrderRepository.NewOrder(idA, null, "x", draft, "Ana", sharedHash, "4321", null, null), (c, o, cu) -> { })).isPresent();

        UUID customerB = orders.findByExternalId(b, idB).orElseThrow().customerId();
        UUID customerA = orders.findByExternalId(a, idA).orElseThrow().customerId();
        assertThat(customerA).isNotNull().isNotEqualTo(customerB);
        assertThat(customers.findByPhoneHash(a, sharedHash).orElseThrow().displayName()).isEqualTo("Ana");
        assertThat(customers.findByPhoneHash(b, sharedHash).orElseThrow().displayName()).isEqualTo("Bea");
        assertThat(customers.count(a)).isEqualTo(1);
        assertThat(customers.count(b)).isEqualTo(1);
    }

    @Test
    void catalog_statements_scope_by_tenant_by_themselves() {
        UUID a = f.tenant();
        UUID b = f.tenant();
        f.seedCatalog(b, "rice", 1500);
        f.seedCatalog(a, "beans", 700);
        String rice = TextKeys.key("rice");
        String beans = TextKeys.key("beans");

        assertThat(catalog.prices(b, Set.of(rice, beans))).containsOnlyKeys(rice);
        assertThat(catalog.prices(a, Set.of(rice, beans))).containsOnlyKeys(beans);
        assertThat(catalog.count(a)).isEqualTo(1);
        assertThat(catalog.count(b)).isEqualTo(1);
    }
}
