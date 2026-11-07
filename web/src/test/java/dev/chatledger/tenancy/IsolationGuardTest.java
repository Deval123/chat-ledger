package dev.chatledger.tenancy;

import dev.chatledger.tenancy.IsolationGuard.Catalog;
import dev.chatledger.tenancy.IsolationGuard.ForeignKeyInfo;
import dev.chatledger.tenancy.IsolationGuard.PolicyInfo;
import dev.chatledger.tenancy.IsolationGuard.TableInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the isolation guard can fail. The real catalog is checked in
 * {@code TenantIsolationCoverageIT}; here the guard is run on invented catalogs
 * that each break exactly one rule, so a green result on the real one means
 * something. No Docker needed.
 */
class IsolationGuardTest {

    private static final String APP = IsolationRegistry.APP_ROLE;

    private static PolicyInfo direct() {
        return new PolicyInfo("tenant_isolation", List.of(APP),
                "(tenant_id = app_current_tenant())", "(tenant_id = app_current_tenant())");
    }

    /** A catalog that matches the registry exactly and follows every rule. */
    private static Catalog healthyCatalog() {
        List<TableInfo> tables = new ArrayList<>();
        IsolationRegistry.TABLES.forEach((name, spec) -> {
            PolicyInfo policy = switch (spec.kind()) {
                case DIRECT -> name.equals("tenant")
                        ? new PolicyInfo("tenant_isolation", List.of(APP), "(id = app_current_tenant())", null)
                        : direct();
                case VIA_PARENT -> new PolicyInfo("tenant_isolation", List.of(APP),
                        "(EXISTS ( SELECT 1 FROM " + spec.parent() + " t WHERE (t.id = " + name + ".txn_id)))", null);
            };
            tables.add(new TableInfo(name, true, !name.equals("tenant") && !name.equals("ledger_entry"),
                    new ArrayList<>(List.of(policy))));
        });
        tables.add(new TableInfo("flyway_schema_history", false, false, new ArrayList<>()));
        return new Catalog(tables, new ArrayList<>(List.of(
                new ForeignKeyInfo("fk_orders_customer_same_tenant", "orders", "customer",
                        List.of("customer_id", "tenant_id")),
                new ForeignKeyInfo("orders_tenant_id_fkey", "orders", "tenant", List.of("tenant_id")),
                new ForeignKeyInfo("ledger_entry_txn_id_fkey", "ledger_entry", "ledger_txn", List.of("txn_id")))),
                Set.of());
    }

    private static List<String> check(Catalog catalog) {
        return IsolationGuard.violations(catalog, IsolationRegistry.TABLES, IsolationRegistry.NON_TENANT_TABLES.keySet(),
                IsolationRegistry.FOREIGN_KEY_EXCEPTIONS, IsolationRegistry.ALLOWED_DEFINER_FUNCTIONS, APP);
    }

    private static Catalog withTable(Catalog base, TableInfo extra) {
        List<TableInfo> tables = new ArrayList<>(base.tables());
        tables.add(extra);
        return new Catalog(tables, base.foreignKeys(), base.definerFunctions());
    }

    private static Catalog withoutTable(Catalog base, String name) {
        return new Catalog(base.tables().stream().filter(t -> !t.name().equals(name)).toList(),
                base.foreignKeys(), base.definerFunctions());
    }

    private static Catalog replacing(Catalog base, String name, TableInfo replacement) {
        return new Catalog(base.tables().stream().map(t -> t.name().equals(name) ? replacement : t).toList(),
                base.foreignKeys(), base.definerFunctions());
    }

    @Test
    void a_healthy_catalog_has_no_violations() {
        assertThat(check(healthyCatalog())).isEmpty();
    }

    @Test
    void a_new_table_that_is_not_registered_is_reported_by_name() {
        var catalog = withTable(healthyCatalog(), new TableInfo("shipment", true, true, List.of(direct())));
        assertThat(check(catalog)).singleElement().asString().contains("'shipment'").contains("not in the isolation registry");
    }

    @Test
    void a_new_table_with_a_tenant_column_and_no_row_level_security_is_reported() {
        var catalog = withTable(healthyCatalog(), new TableInfo("shipment", false, true, List.of()));
        assertThat(check(catalog)).anyMatch(v -> v.contains("'shipment'"));
    }

    @Test
    void a_registered_table_without_row_level_security_is_reported() {
        var catalog = replacing(healthyCatalog(), "orders", new TableInfo("orders", false, true, List.of(direct())));
        assertThat(check(catalog)).anyMatch(v -> v.contains("'orders'") && v.contains("row-level security enabled"));
    }

    @Test
    void a_registered_table_with_no_policy_for_the_application_role_is_reported() {
        var catalog = replacing(healthyCatalog(), "customer", new TableInfo("customer", true, true,
                List.of(new PolicyInfo("other", List.of("somebody_else"), "(tenant_id = app_current_tenant())", null))));
        assertThat(check(catalog)).anyMatch(v -> v.contains("'customer'") && v.contains("no policy for role"));
    }

    @Test
    void a_blanket_true_policy_is_reported() {
        var catalog = replacing(healthyCatalog(), "payment", new TableInfo("payment", true, true,
                List.of(new PolicyInfo("open", List.of(APP), "true", "true"))));
        assertThat(check(catalog)).anyMatch(v -> v.contains("'payment'") && v.contains("every row through"));
    }

    @Test
    void a_policy_that_ignores_the_current_tenant_is_reported() {
        var catalog = replacing(healthyCatalog(), "flag", new TableInfo("flag", true, true,
                List.of(new PolicyInfo("wrong", List.of(APP), "(tenant_id IS NOT NULL)", null))));
        assertThat(check(catalog)).anyMatch(v -> v.contains("'flag'") && v.contains("app_current_tenant()"));
    }

    @Test
    void a_policy_that_applies_to_public_is_still_checked() {
        var catalog = replacing(healthyCatalog(), "flag", new TableInfo("flag", true, true,
                List.of(new PolicyInfo("wrong", List.of("public"), "true", null))));
        assertThat(check(catalog)).anyMatch(v -> v.contains("'flag'"));
    }

    @Test
    void a_single_column_foreign_key_between_tenant_tables_is_reported() {
        var base = healthyCatalog();
        var foreignKeys = new ArrayList<>(base.foreignKeys());
        foreignKeys.add(new ForeignKeyInfo("orders_customer_id_fkey", "orders", "customer", List.of("customer_id")));
        var catalog = new Catalog(base.tables(), foreignKeys, base.definerFunctions());
        assertThat(check(catalog)).singleElement().asString()
                .contains("orders_customer_id_fkey").contains("does not include tenant_id");
    }

    @Test
    void a_foreign_key_from_a_table_without_tenant_id_needs_a_recorded_exception() {
        var base = healthyCatalog();
        var foreignKeys = new ArrayList<>(base.foreignKeys());
        foreignKeys.add(new ForeignKeyInfo("fk_x", "order_line", "orders", List.of("order_id")));
        var tables = new ArrayList<>(base.tables());
        tables.replaceAll(t -> t.name().equals("order_line")
                ? new TableInfo("order_line", true, false, t.policies()) : t);
        var catalog = new Catalog(tables, foreignKeys, base.definerFunctions());
        assertThat(check(catalog)).anyMatch(v -> v.contains("fk_x") && v.contains("no recorded exception"));
    }

    @Test
    void an_unlisted_security_definer_function_is_reported() {
        var base = healthyCatalog();
        var catalog = new Catalog(base.tables(), base.foreignKeys(), Set.of("sneaky_function"));
        assertThat(check(catalog)).singleElement().asString().contains("sneaky_function");
    }

    @Test
    void a_registry_entry_for_a_table_that_no_longer_exists_is_reported() {
        var catalog = withoutTable(healthyCatalog(), "flag");
        assertThat(check(catalog)).singleElement().asString().contains("'flag'").contains("does not exist");
    }

    @Test
    void a_follow_the_parent_table_must_refer_to_its_parent() {
        var catalog = replacing(healthyCatalog(), "ledger_entry", new TableInfo("ledger_entry", true, false,
                List.of(new PolicyInfo("tenant_isolation", List.of(APP), "(EXISTS ( SELECT 1 FROM account a ))", null))));
        assertThat(check(catalog)).anyMatch(v -> v.contains("'ledger_entry'") && v.contains("ledger_txn"));
    }
}
