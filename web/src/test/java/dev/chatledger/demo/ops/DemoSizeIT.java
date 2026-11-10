package dev.chatledger.demo.ops;

import dev.chatledger.demo.Scenario;
import dev.chatledger.demo.Script;
import dev.chatledger.demo.web.DemoScenarios;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static dev.chatledger.demo.ops.DemoWorld.ownerOne;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures what a demo workspace costs (task 9, D25): its rows after the seed and after a typical visit (the seven scenarios once and three
 * decisions), and the growth of the database for ten workspaces of each kind. The numbers are printed for the notes; the bounds asserted
 * are loose (a workspace well under 2 MB, rows under 400), so a change that multiplies the cost fails here. Measured on the test
 * container (PostgreSQL 16.4), not on a hosted tier.
 *
 * <p>Task 9.
 */
class DemoSizeIT {

    private static final String[] TABLES = {"tenant", "app_user", "demo_tenant", "catalog_item", "customer", "orders", "order_line", "payment", "match",
            "flag", "account", "ledger_txn", "audit_log"};

    static long rows(UUID tenant) {
        long n = Long.parseLong(ownerOne("SELECT count(*)::text FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id WHERE t.tenant_id = ?", tenant));
        for (String table : TABLES) {
            String column = table.equals("tenant") ? "id" : "tenant_id";
            n += Long.parseLong(ownerOne("SELECT count(*)::text FROM " + table + " WHERE " + column + " = ?", tenant));
        }
        return n;
    }

    private static long databaseBytes() {
        DemoWorld.column(DemoWorld.DB.superDataSource(), "CHECKPOINT");
        return Long.parseLong(DemoWorld.scalar(DemoWorld.DB.superDataSource(), "SELECT pg_database_size(current_database())::text"));
    }

    private static void visit(DemoWorld.Workspace w) {
        DemoCommand.Services services = DemoWorld.services();
        DemoScenarios scenarios = new DemoScenarios(services.orders(), services.payments(), services.messages());
        EnumMap<Scenario, Script> store = new EnumMap<>(Scenario.class);
        for (Scenario s : Scenario.values()) {
            scenarios.run(w.tenant(), w.seller(), s, DemoScenarios.Step.BOTH, store);
        }
        // Three decisions: the suggestions of three scenarios confirmed.
        for (String id : DemoWorld.owner("SELECT id::text FROM match WHERE tenant_id = ? AND state = 'SUGGESTED' ORDER BY created_at DESC LIMIT 3", w.tenant())) {
            services.matcher().confirm(w.tenant(), UUID.fromString(id), w.seller());
        }
    }

    @Test
    void one_workspace_after_its_seed_and_after_a_visit() {
        DemoWorld.retireEverythingWaiting();
        long start = databaseBytes();
        List<DemoWorld.Workspace> made = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            made.add(DemoWorld.seeded());
        }
        long seeded = databaseBytes();
        long seedRows = rows(made.get(0).tenant());
        made.forEach(DemoSizeIT::visit);
        long visited = databaseBytes();
        long visitRows = rows(made.get(0).tenant());
        long perSeed = (seeded - start) / 10;
        long perVisit = (visited - seeded) / 10;
        System.out.println("DEMO-SIZE rows after seed=" + seedRows + " rows after visit=" + visitRows + " bytes per seeded workspace=" + perSeed
                + " bytes per visit=" + perVisit + " (database growth over ten of each, PostgreSQL " + ownerOne("SHOW server_version") + ")");
        assertThat(seedRows).isBetween(100L, 400L);
        assertThat(visitRows).isGreaterThan(seedRows).isLessThan(400L);
        assertThat(perSeed + perVisit).as("a visited workspace is well under 2 MB").isLessThan(2L * 1024 * 1024);
        made.forEach(DemoWorld::retire);
    }
}
