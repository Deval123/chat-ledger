package dev.chatledger.tenancy;

import dev.chatledger.tenancy.IsolationRegistry.Kind;
import dev.chatledger.tenancy.IsolationRegistry.TableSpec;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a snapshot of the database catalog against the isolation registry and
 * returns every violation it finds. It is a pure function over a snapshot, so the
 * same rules run on the real catalog and, in a unit test, on invented catalogs
 * that are meant to fail.
 *
 * <p>Rules:
 * <ol>
 *   <li>Every table is either registered or listed as holding no tenant data.</li>
 *   <li>Every registered table exists, has row-level security enabled, and has a
 *       policy for the application role that is not a blanket {@code true}.</li>
 *   <li>A DIRECT policy calls {@code app_current_tenant()}; a VIA_PARENT policy
 *       refers to a parent that is itself DIRECT.</li>
 *   <li>A foreign key from a tenant table to another tenant table includes
 *       {@code tenant_id}, because foreign-key checks bypass row-level security.</li>
 *   <li>Only registered SECURITY DEFINER functions exist.</li>
 * </ol>
 */
final class IsolationGuard {

    record PolicyInfo(String name, List<String> roles, String using, String withCheck) {}

    record TableInfo(String name, boolean rowSecurity, boolean hasTenantColumn, List<PolicyInfo> policies) {}

    record ForeignKeyInfo(String name, String table, String referencedTable, List<String> columns) {}

    record Catalog(List<TableInfo> tables, List<ForeignKeyInfo> foreignKeys, Set<String> definerFunctions) {}

    private IsolationGuard() {
    }

    static List<String> violations(Catalog catalog, Map<String, TableSpec> registry,
                                   Set<String> nonTenantTables, Map<String, String> fkExceptions,
                                   Set<String> allowedDefiners, String appRole) {
        List<String> found = new ArrayList<>();
        Set<String> present = new HashSet<>();

        for (TableInfo table : catalog.tables()) {
            present.add(table.name());
            if (nonTenantTables.contains(table.name())) {
                continue;
            }
            TableSpec spec = registry.get(table.name());
            if (spec == null) {
                found.add("table '" + table.name() + "' is not in the isolation registry: give it row-level "
                        + "security and a policy, then register it with an insert probe (or list it as holding no tenant data)");
                continue;
            }
            if (!table.rowSecurity()) {
                found.add("table '" + table.name() + "' does not have row-level security enabled");
            }
            List<PolicyInfo> forApp = table.policies().stream()
                    .filter(p -> p.roles().contains(appRole) || p.roles().contains("public")).toList();
            if (forApp.isEmpty()) {
                found.add("table '" + table.name() + "' has no policy for role " + appRole);
            }
            for (PolicyInfo policy : forApp) {
                checkPolicy(found, table.name(), spec, registry, policy);
            }
        }

        for (String registered : registry.keySet()) {
            if (!present.contains(registered)) {
                found.add("registry names table '" + registered + "' which does not exist");
            }
        }

        Set<String> withTenantColumn = new HashSet<>();
        catalog.tables().stream().filter(TableInfo::hasTenantColumn).forEach(t -> withTenantColumn.add(t.name()));
        for (ForeignKeyInfo fk : catalog.foreignKeys()) {
            if (nonTenantTables.contains(fk.table())) {
                continue;
            }
            if (withTenantColumn.contains(fk.table())) {
                if (!"tenant".equals(fk.referencedTable()) && !fk.columns().contains("tenant_id")) {
                    found.add("foreign key '" + fk.name() + "' from '" + fk.table() + "' to '" + fk.referencedTable()
                            + "' does not include tenant_id, so it could point at another tenant's row");
                }
            } else if (!fkExceptions.containsKey(fk.table())) {
                found.add("foreign key '" + fk.name() + "' from '" + fk.table() + "', a table with no tenant_id column, "
                        + "has no recorded exception");
            }
        }

        for (String definer : catalog.definerFunctions()) {
            if (!allowedDefiners.contains(definer)) {
                found.add("SECURITY DEFINER function '" + definer + "' is not on the allowlist");
            }
        }
        return found;
    }

    private static void checkPolicy(List<String> found, String table, TableSpec spec,
                                    Map<String, TableSpec> registry, PolicyInfo policy) {
        String using = normalise(policy.using());
        if (using.isEmpty() || using.equals("true") || using.equals("(true)")) {
            found.add("policy '" + policy.name() + "' on '" + table + "' lets every row through");
            return;
        }
        if (spec.kind() == Kind.DIRECT) {
            if (!using.contains("app_current_tenant()")) {
                found.add("policy '" + policy.name() + "' on '" + table + "' does not compare with app_current_tenant()");
            }
        } else {
            TableSpec parent = registry.get(spec.parent());
            if (parent == null || parent.kind() != Kind.DIRECT) {
                found.add("table '" + table + "' follows parent '" + spec.parent() + "' which is not a DIRECT table");
            }
            if (!using.contains(spec.parent())) {
                found.add("policy '" + policy.name() + "' on '" + table + "' does not refer to its parent '" + spec.parent() + "'");
            }
        }
        // An absent WITH CHECK means "same as USING", which was just checked. Only an explicit
        // blanket 'true' is a separate violation.
        String check = normalise(policy.withCheck());
        if (check.equals("true") || check.equals("(true)")) {
            found.add("policy '" + policy.name() + "' on '" + table + "' lets every row be written");
        }
    }

    private static String normalise(String expression) {
        return expression == null ? "" : expression.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    // ── Loading a snapshot from a live database ─────────────────────────────

    static Catalog load(Connection c) throws SQLException {
        List<TableInfo> tables = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT c.relname, c.relrowsecurity, "
                             + "EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid "
                             + "        AND a.attname = 'tenant_id' AND NOT a.attisdropped) "
                             + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                             + "WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') ORDER BY c.relname")) {
            while (rs.next()) {
                tables.add(new TableInfo(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), new ArrayList<>()));
            }
        }
        Map<String, TableInfo> byName = new java.util.HashMap<>();
        tables.forEach(t -> byName.put(t.name(), t));

        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT c.relname, p.polname, "
                             + "ARRAY(SELECT rolname FROM pg_roles WHERE oid = ANY (p.polroles)) "
                             + "  || CASE WHEN 0 = ANY (p.polroles) THEN ARRAY['public'] ELSE ARRAY[]::text[] END, "
                             + "pg_get_expr(p.polqual, p.polrelid), pg_get_expr(p.polwithcheck, p.polrelid) "
                             + "FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid "
                             + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'")) {
            while (rs.next()) {
                TableInfo table = byName.get(rs.getString(1));
                if (table != null) {
                    table.policies().add(new PolicyInfo(rs.getString(2), strings(rs.getArray(3)),
                            rs.getString(4), rs.getString(5)));
                }
            }
        }

        List<ForeignKeyInfo> foreignKeys = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT con.conname, src.relname, ref.relname, "
                             + "ARRAY(SELECT a.attname::text FROM pg_attribute a "
                             + "      WHERE a.attrelid = con.conrelid AND a.attnum = ANY (con.conkey)) "
                             + "FROM pg_constraint con "
                             + "JOIN pg_class src ON src.oid = con.conrelid "
                             + "JOIN pg_class ref ON ref.oid = con.confrelid "
                             + "JOIN pg_namespace n ON n.oid = src.relnamespace "
                             + "WHERE con.contype = 'f' AND n.nspname = 'public'")) {
            while (rs.next()) {
                foreignKeys.add(new ForeignKeyInfo(rs.getString(1), rs.getString(2), rs.getString(3),
                        strings(rs.getArray(4))));
            }
        }

        Set<String> definers = new HashSet<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                             + "WHERE n.nspname = 'public' AND p.prosecdef")) {
            while (rs.next()) {
                definers.add(rs.getString(1));
            }
        }
        return new Catalog(tables, foreignKeys, definers);
    }

    private static List<String> strings(Array array) throws SQLException {
        return Arrays.stream((Object[]) array.getArray()).map(Object::toString).toList();
    }
}
