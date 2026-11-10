package dev.chatledger.demo.ops;

import dev.chatledger.ledger.LedgerDatabase;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.chatledger.demo.ops.DemoWorld.DB;
import static dev.chatledger.demo.ops.DemoWorld.owner;
import static dev.chatledger.demo.ops.DemoWorld.ownerOne;
import static dev.chatledger.demo.ops.DemoWorld.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V020 in the database itself (task 9, D1, D5, D7, D12). The guarantee that a reset can never touch a real workspace is tested one layer
 * at a time: on a regular database every demo function refuses; on a demo database a forged regular workspace is refused by each
 * function; the kind and the code of a workspace never change, for the owner too; the demo operator has no table privilege and the
 * application only two functions. Retiring overwrites exactly the personal-data columns and leaves the ledger, the audit log and the
 * flags byte for byte as they were. Nothing is ever deleted.
 *
 * <p>Requirements 7, 9.1. Task 9.
 */
class DemoSchemaIT {

    private static final LedgerDatabase REGULAR = LedgerDatabase.get();

    private static String state(SQLException e) {
        return e.getSQLState();
    }

    private static void exec(DataSource ds, String sql, Object... args) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.execute();
        }
    }

    /** Written as the superuser with triggers and foreign keys off: a state the schema refuses, to show the functions do not trust it. */
    private static void forced(DataSource superuser, String sql, Object... args) throws SQLException {
        try (Connection c = superuser.getConnection()) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                s.execute("SET LOCAL session_replication_role = replica");
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.execute();
            }
            c.commit();
        }
    }

    private static String hash() {
        return dev.chatledger.security.PasswordHashing.create().encode(UUID.randomUUID().toString());
    }

    // ── Layer 1: a regular database refuses every demo function ─────────────

    @Test
    void on_a_regular_database_every_demo_function_refuses_and_init_cannot_turn_it_into_a_demo() {
        DataSource op = REGULAR.demoOperatorDataSource();
        UUID tenant = REGULAR.createTenant();
        for (String sql : new String[]{"SELECT demo_create_tenant('" + hash() + "', 30)",
                "SELECT demo_seed_catalog('" + tenant + "', '[]'::jsonb)", "SELECT demo_mark_ready('" + tenant + "', 1, 1)",
                "SELECT demo_due_for_retirement('1 hour', '1 hour')", "SELECT demo_retire('" + tenant + "')"}) {
            assertThatThrownBy(() -> exec(op, sql)).as(sql).isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(state((SQLException) e)).isEqualTo("55000"));
        }
        assertThatThrownBy(() -> exec(op, "SELECT demo_init()")).hasMessageContaining("must hold no regular workspace");
        assertThat(scalar(REGULAR.appDataSource(), "SELECT count(*)::text FROM demo_claim('30 minutes')")).isEqualTo("0");
        assertThat(scalar(REGULAR.appDataSource(), "SELECT demo_mode()")).isEqualTo("REGULAR");
        assertThat(scalar(REGULAR.migrateDataSource(), "SELECT disabled_at IS NULL FROM tenant WHERE id = ?", tenant)).isEqualTo("t");
    }

    @Test
    void on_a_regular_database_a_demo_workspace_that_should_not_be_there_is_still_refused_by_the_mode() throws SQLException {
        // Written and probed in one superuser transaction that is rolled back, so the regular database never keeps it.
        UUID tenant = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        try (Connection c = REGULAR.superDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                run(c, "INSERT INTO tenant(id, name, currency, scale, slug, kind) VALUES (?, 'Odd', 'XAF', 0, ?, 'DEMO')", tenant,
                        "demo-" + tenant.toString().substring(0, 8));
                run(c, "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'SELLER')", seller, tenant,
                        "s-" + seller.toString().substring(0, 8) + "@example.test", hash());
                run(c, "INSERT INTO demo_tenant(tenant_id, seller_id, state, ready_at) VALUES (?, ?, 'READY', now())", tenant, seller);
                run(c, "SET LOCAL ROLE " + LedgerDatabase.APP_USER);
                assertThat(one(c, "SELECT out_tenant_id::text FROM demo_claim('30 minutes')")).as("never handed out on a regular database").isNull();
                run(c, "RESET ROLE");
                run(c, "SET LOCAL ROLE " + LedgerDatabase.DEMO_USER);
                for (String sql : new String[]{"SELECT demo_retire(?)", "SELECT demo_seed_catalog(?, '[{\"name_key\":\"x\",\"display_name\":\"X\",\"unit_minor\":1}]'::jsonb)"}) {
                    run(c, "SAVEPOINT probe");
                    assertThatThrownBy(() -> run(c, sql, tenant)).as(sql).hasMessageContaining("not a demo deployment");
                    run(c, "ROLLBACK TO SAVEPOINT probe");
                }
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void a_regular_workspace_has_no_row_cap() throws SQLException {
        UUID tenant = REGULAR.createTenant();
        exec(REGULAR.migrateDataSource(), "INSERT INTO orders(id, tenant_id, external_id, total_minor, currency, status, raw_text) "
                + "SELECT gen_random_uuid(), ?, 'many-' || g, NULL, 'XAF', 'UNPARSED', 'filler' FROM generate_series(1, 301) g", tenant);
        assertThat(scalar(REGULAR.migrateDataSource(), "SELECT count(*)::text FROM orders WHERE tenant_id = ?", tenant)).isEqualTo("301");
    }

    // ── Layer 2: on a demo database, a regular workspace is refused by each function ──

    @Test
    void on_a_demo_database_no_real_workspace_can_be_provisioned_and_a_forged_one_is_refused_by_every_function() throws SQLException {
        DemoWorld.init();
        assertThatThrownBy(() -> exec(DB.provisionerDataSource(), "SELECT * FROM provision_tenant('shop-on-demo', 'Shop', 'a@example.test', ?)", hash()))
                .satisfies(e -> assertThat(state((SQLException) e)).isEqualTo("55000"));
        DemoWorld.Workspace demo = DemoWorld.created();
        String demoSlug = ownerOne("SELECT slug FROM tenant WHERE id = ?", demo.tenant());
        // Password sign-in never finds a demo workspace, and nobody can be given a password to one.
        assertThat(scalar(DB.appDataSource(), "SELECT resolve_tenant_for_login(?) IS NULL", demoSlug)).isEqualTo("t");
        assertThatThrownBy(() -> exec(DB.provisionerDataSource(), "SELECT provision_user(?, 'x@example.test', 'HELPER', ?)", demoSlug, hash()))
                .satisfies(e -> assertThat(state((SQLException) e)).isEqualTo("P0002"));
        DemoWorld.retire(demo);

        // A regular workspace that should not exist here, with a demo row forged for it: written and probed in one superuser transaction that
        // is rolled back, each function called under the role that holds it (SET ROLE), so the forged rows never outlive the test.
        UUID forged = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        String forgedSlug = "forged-" + forged.toString().substring(0, 8);
        try (Connection c = DB.superDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                run(c, "INSERT INTO tenant(id, name, currency, scale, slug) VALUES (?, 'Forged', 'XAF', 0, ?)", forged, forgedSlug);
                run(c, "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'SELLER')", seller, forged,
                        "s-" + forged.toString().substring(0, 8) + "@example.test", hash());
                run(c, "SET LOCAL session_replication_role = replica");
                run(c, "INSERT INTO demo_tenant(tenant_id, seller_id, state, ready_at) VALUES (?, ?, 'READY', now())", forged, seller);
                run(c, "SET LOCAL session_replication_role = origin");
                run(c, "SET LOCAL ROLE " + LedgerDatabase.DEMO_USER);
                for (String[] call : new String[][]{{"SELECT demo_retire(?)", "not a demo workspace"},
                        {"SELECT demo_seed_catalog(?, '[{\"name_key\":\"x\",\"display_name\":\"X\",\"unit_minor\":1}]'::jsonb)", "not a demo workspace"}}) {
                    run(c, "SAVEPOINT probe");
                    assertThatThrownBy(() -> run(c, call[0], forged)).as(call[0]).hasMessageContaining(call[1]);
                    run(c, "ROLLBACK TO SAVEPOINT probe");
                }
                assertThat(one(c, "SELECT demo_mark_ready(?, 1, 1)::text", forged)).isEqualTo("false");
                run(c, "RESET ROLE");
                run(c, "SET LOCAL ROLE " + LedgerDatabase.APP_USER);
                for (int i = 0; i < 60; i++) {
                    String claimed = one(c, "SELECT out_tenant_id::text FROM demo_claim('30 minutes')");
                    assertThat(claimed).as("the forged workspace is never handed out").isNotEqualTo(forged.toString());
                    if (claimed == null) {
                        break;
                    }
                }
                assertThat(one(c, "SELECT (resolve_tenant_for_login(?) IS NOT NULL)::text", forgedSlug)).as("sign-in finds the regular one").isEqualTo("true");
                run(c, "RESET ROLE");
                assertThat(one(c, "SELECT (disabled_at IS NULL)::text || ' ' || (SELECT count(*) FROM catalog_item WHERE tenant_id = ?) || ' ' "
                        + "|| (SELECT state FROM demo_tenant WHERE tenant_id = ?) FROM tenant WHERE id = ?", forged, forged, forged)).isEqualTo("true 0 READY");
            } finally {
                c.rollback();
            }
        }
    }

    private static void run(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.execute();
        }
    }

    private static String one(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ── Layer 3: the kind and the code of a workspace never change ──────────

    @Test
    void the_kind_and_the_code_of_a_workspace_never_change_even_for_the_owner() {
        DemoWorld.Workspace demo = DemoWorld.created();
        assertThatThrownBy(() -> exec(DB.migrateDataSource(), "UPDATE tenant SET kind = 'REGULAR' WHERE id = ?", demo.tenant()))
                .hasMessageContaining("cannot change");
        assertThatThrownBy(() -> exec(DB.migrateDataSource(), "UPDATE tenant SET slug = 'demo-renamed' WHERE id = ?", demo.tenant()))
                .hasMessageContaining("cannot change");
        assertThatThrownBy(() -> exec(DB.migrateDataSource(), "INSERT INTO tenant(id, name, currency, scale, slug) VALUES (gen_random_uuid(), 'x', 'XAF', 0, 'demo-looking')"))
                .as("a regular workspace cannot take a demo code").hasMessageContaining("chk_tenant_kind");
        assertThatThrownBy(() -> exec(DB.migrateDataSource(), "INSERT INTO tenant(id, name, currency, scale, slug, kind) VALUES (gen_random_uuid(), 'x', 'XAF', 0, 'plain-code', 'DEMO')"))
                .as("a demo workspace has a demo code").hasMessageContaining("chk_tenant_kind");
        assertThatThrownBy(() -> exec(REGULAR.migrateDataSource(), "INSERT INTO tenant(id, name, currency, scale, slug) VALUES (gen_random_uuid(), 'x', 'XAF', 0, 'demo-on-regular')"))
                .hasMessageContaining("chk_tenant_kind");
        // A demo row can only name a demo workspace, for the owner too: the key includes the kind.
        UUID regular = REGULAR.createTenant();
        UUID user = UUID.randomUUID();
        try {
            exec(REGULAR.migrateDataSource(), "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'SELLER')", user, regular,
                    "u-" + user.toString().substring(0, 8) + "@example.test", hash());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThatThrownBy(() -> exec(REGULAR.migrateDataSource(), "INSERT INTO demo_tenant(tenant_id, seller_id, state) VALUES (?, ?, 'SEEDING')", regular, user))
                .hasMessageContaining("fk_demo_tenant_is_demo");
        assertThatThrownBy(() -> exec(DB.migrateDataSource(), "INSERT INTO demo_tenant(tenant_id, tenant_kind, seller_id, state) VALUES (?, 'REGULAR', ?, 'SEEDING')",
                demo.tenant(), demo.seller())).hasMessageContaining("demo_tenant_tenant_kind_check");
        DemoWorld.retire(demo);
    }

    @Test
    void the_mode_moves_once_from_regular_to_demo_and_a_demo_workspace_only_forward() throws SQLException {
        assertThatThrownBy(() -> exec(DB.migrateDataSource(), "UPDATE deployment_mode SET mode = 'REGULAR'")).hasMessageContaining("only moves from REGULAR to DEMO");
        assertThatThrownBy(() -> exec(DB.migrateDataSource(), "DELETE FROM deployment_mode")).hasMessageContaining("cannot be added, deleted or truncated");
        assertThatThrownBy(() -> exec(DB.migrateDataSource(), "TRUNCATE deployment_mode")).hasMessageContaining("cannot be added, deleted or truncated");
        assertThatThrownBy(() -> exec(DB.migrateDataSource(), "INSERT INTO deployment_mode(id, mode) VALUES (2, 'DEMO')")).isInstanceOf(SQLException.class);
        // On a regular database the one allowed move exists (shown and rolled back, so the shared database stays regular).
        try (Connection c = REGULAR.migrateDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("UPDATE deployment_mode SET mode = 'DEMO'")) {
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            c.rollback();
        }
        assertThat(scalar(REGULAR.appDataSource(), "SELECT demo_mode()")).isEqualTo("REGULAR");
        DemoWorld.Workspace w = DemoWorld.created();
        assertThatThrownBy(() -> exec(DB.migrateDataSource(), "UPDATE demo_tenant SET state = 'CLAIMED', claimed_at = now(), ready_at = now(), "
                + "lease_ends_at = now() + interval '1 hour' WHERE tenant_id = ?", w.tenant())).hasMessageContaining("cannot move from SEEDING to CLAIMED");
        assertThatThrownBy(() -> exec(DB.migrateDataSource(), "DELETE FROM demo_tenant WHERE tenant_id = ?", w.tenant())).hasMessageContaining("never deleted");
        // Another seller of the same workspace (the foreign key accepts it): only the trigger refuses the change.
        UUID other = UUID.randomUUID();
        exec(DB.migrateDataSource(), "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'SELLER')", other, w.tenant(),
                "other-" + other.toString().substring(0, 8) + "@example.test", hash());
        assertThatThrownBy(() -> exec(DB.migrateDataSource(), "UPDATE demo_tenant SET seller_id = ? WHERE tenant_id = ?", other, w.tenant()))
                .hasMessageContaining("cannot change its identity");
        // A lease that ends when it starts is refused by the check (READY to CLAIMED is an allowed move).
        DemoWorld.Workspace ready = DemoWorld.seeded();
        assertThatThrownBy(() -> exec(DB.migrateDataSource(), "UPDATE demo_tenant SET state = 'CLAIMED', claimed_at = now(), lease_ends_at = now() "
                + "WHERE tenant_id = ?", ready.tenant())).hasMessageContaining("chk_demo_tenant_state");
        DemoWorld.retire(ready);
        DemoWorld.retire(w);
        assertThatThrownBy(() -> exec(DB.migrateDataSource(), "UPDATE demo_tenant SET state = 'READY', ready_at = now(), retired_at = NULL WHERE tenant_id = ?",
                w.tenant())).hasMessageContaining("cannot move from RETIRED to READY");
    }

    // ── Privileges ─────────────────────────────────────────────────────────

    @Test
    void the_application_calls_two_demo_functions_and_the_operator_reads_no_table() {
        List<String> appMay = owner("SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' "
                + "AND p.proname LIKE 'demo%' AND p.prorettype <> 'trigger'::regtype AND has_function_privilege(?, p.oid, 'EXECUTE') ORDER BY 1",
                LedgerDatabase.APP_USER);
        assertThat(appMay).containsExactly("demo_claim", "demo_mode", "demo_row_cap");
        List<String> operatorMay = owner("SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' "
                + "AND p.prosecdef AND has_function_privilege(?, p.oid, 'EXECUTE') ORDER BY 1", LedgerDatabase.DEMO_USER);
        assertThat(operatorMay).containsExactly("demo_create_tenant", "demo_due_for_retirement", "demo_init", "demo_mark_ready", "demo_refresh_lock",
                "demo_retire", "demo_seed_catalog");
        assertThat(owner("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind = 'r' "
                + "AND (has_table_privilege(?, c.oid, 'SELECT') OR has_table_privilege(?, c.oid, 'INSERT') OR has_table_privilege(?, c.oid, 'UPDATE') "
                + "OR has_table_privilege(?, c.oid, 'DELETE'))", LedgerDatabase.DEMO_USER, LedgerDatabase.DEMO_USER, LedgerDatabase.DEMO_USER,
                LedgerDatabase.DEMO_USER)).as("the demo operator reads and writes no table").isEmpty();
        assertThat(owner("SELECT c.relname FROM pg_class c WHERE c.relname IN ('deployment_mode', 'demo_tenant') "
                + "AND (has_table_privilege(?, c.oid, 'SELECT') OR has_table_privilege(?, c.oid, 'INSERT') OR has_table_privilege(?, c.oid, 'UPDATE'))",
                LedgerDatabase.APP_USER, LedgerDatabase.APP_USER, LedgerDatabase.APP_USER)).as("the application touches neither new table").isEmpty();
        assertThatThrownBy(() -> exec(DB.demoOperatorDataSource(), "SELECT * FROM demo_claim('30 minutes')")).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> exec(DB.appDataSource(), "SELECT demo_retire(gen_random_uuid())")).hasMessageContaining("permission denied");
    }

    // ── Bounds of the functions ────────────────────────────────────────────

    @Test
    void the_lease_the_pool_and_the_catalog_are_bounded_and_an_unfinished_seed_is_not_ready() {
        assertThatThrownBy(() -> exec(DB.appDataSource(), "SELECT * FROM demo_claim('9 minutes')")).hasMessageContaining("between 10 minutes and 4 hours");
        assertThatThrownBy(() -> exec(DB.appDataSource(), "SELECT * FROM demo_claim('4 hours 1 second')")).hasMessageContaining("between 10 minutes and 4 hours");
        assertThatThrownBy(() -> exec(DB.demoOperatorDataSource(), "SELECT * FROM demo_create_tenant(?, 9)", hash())).hasMessageContaining("between 10 and 50");
        assertThatThrownBy(() -> exec(DB.demoOperatorDataSource(), "SELECT * FROM demo_create_tenant(?, 51)", hash())).hasMessageContaining("between 10 and 50");
        DemoWorld.retireEverythingWaiting();
        List<DemoWorld.Workspace> made = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            made.add(DemoWorld.created());
        }
        assertThat(scalar(DB.demoOperatorDataSource(), "SELECT count(*)::text FROM demo_create_tenant(?, 10)", hash())).as("a full pool").isEqualTo("0");
        DemoWorld.Workspace w = made.get(0);
        assertThat(scalar(DB.demoOperatorDataSource(), "SELECT demo_mark_ready(?, 10, 8)", w.tenant())).as("nothing seeded yet").isEqualTo("f");
        assertThat(ownerOne("SELECT state FROM demo_tenant WHERE tenant_id = ?", w.tenant())).isEqualTo("SEEDING");
        assertThatThrownBy(() -> exec(DB.demoOperatorDataSource(), "SELECT demo_mark_ready(?, 0, 8)", w.tenant())).hasMessageContaining("positive");
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            many.append(i == 0 ? "" : ",").append("{\"name_key\":\"k").append(i).append("\",\"display_name\":\"K\",\"unit_minor\":1}");
        }
        assertThatThrownBy(() -> exec(DB.demoOperatorDataSource(), "SELECT demo_seed_catalog(?, CAST(? AS JSONB))", w.tenant(), many.append(']').toString()))
                .hasMessageContaining("1 to 50");
        assertThatThrownBy(() -> exec(DB.demoOperatorDataSource(), "SELECT demo_seed_catalog(?, CAST('{}' AS JSONB))", w.tenant())).hasMessageContaining("1 to 50");
        assertThatThrownBy(() -> exec(DB.demoOperatorDataSource(), "SELECT demo_seed_catalog(?, CAST(? AS JSONB))", w.tenant(),
                "[{\"name_key\":\"x\",\"display_name\":\"X\",\"unit_minor\":0}]")).as("the table's own CHECK").isInstanceOf(SQLException.class);
        assertThat(scalar(DB.demoOperatorDataSource(), "SELECT demo_seed_catalog(?, CAST(? AS JSONB))", w.tenant(), DemoRefresh.catalogJson())).isEqualTo("16");
        assertThat(scalar(DB.demoOperatorDataSource(), "SELECT demo_seed_catalog(?, CAST(? AS JSONB))", w.tenant(), DemoRefresh.catalogJson())).as("a repeat")
                .isEqualTo("0");
        made.forEach(DemoWorld::retire);
        assertThatThrownBy(() -> exec(DB.demoOperatorDataSource(), "SELECT demo_seed_catalog(?, CAST(? AS JSONB))", w.tenant(), DemoRefresh.catalogJson()))
                .as("a retired workspace is not being seeded").hasMessageContaining("being seeded");
    }

    @Test
    void a_workspace_ready_for_more_than_three_hours_is_not_handed_out_and_is_due() throws SQLException {
        DemoWorld.retireEverythingWaiting();
        DemoWorld.Workspace stale = DemoWorld.seeded();
        forced(DB.superDataSource(), "UPDATE demo_tenant SET ready_at = now() - interval '3 hours 1 minute' WHERE tenant_id = ?", stale.tenant());
        assertThat(DemoWorld.claim()).isNull();
        assertThat(DemoWorld.column(DB.demoOperatorDataSource(), "SELECT demo_due_for_retirement('3 hours', '10 minutes')")).contains(stale.tenant().toString());
        DemoWorld.retire(stale);
    }

    // ── One workspace per visitor ──────────────────────────────────────────

    @Test
    void sixteen_visitors_at_once_get_distinct_workspaces_and_the_rest_get_none() throws Exception {
        DemoWorld.retireEverythingWaiting();
        Set<UUID> ready = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            ready.add(DemoWorld.seeded().tenant());
        }
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DemoWorld.Workspace>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return DemoWorld.claim();
                }));
            }
            start.countDown();
            List<UUID> got = new ArrayList<>();
            for (Future<DemoWorld.Workspace> r : results) {
                DemoWorld.Workspace w = r.get();
                if (w != null) {
                    got.add(w.tenant());
                }
            }
            assertThat(got).hasSize(5).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ready);
        } finally {
            pool.shutdownNow();
        }
        for (UUID t : ready) {
            assertThat(ownerOne("SELECT state || ' ' || (lease_ends_at - claimed_at = interval '30 minutes')::text FROM demo_tenant WHERE tenant_id = ?", t))
                    .isEqualTo("CLAIMED true");
            DemoWorld.retire(new DemoWorld.Workspace(t, null));
        }
    }

    // ── Retire: overwritten, never deleted; the books untouched ─────────────

    private static String books(UUID tenant) {
        return ownerOne("SELECT md5(string_agg(x, '|' ORDER BY x)) FROM ("
                + "SELECT 'e' || e.id || e.txn_id || e.account_id || e.debit_minor || e.credit_minor AS x FROM ledger_entry e JOIN ledger_txn t ON t.id = e.txn_id WHERE t.tenant_id = ? "
                + "UNION ALL SELECT 't' || t.id || t.kind || t.ref_type || t.ref_id || t.created_at FROM ledger_txn t WHERE t.tenant_id = ? "
                + "UNION ALL SELECT 'a' || a.id || a.action || a.subject || a.at || COALESCE(a.actor::text, '') FROM audit_log a WHERE a.tenant_id = ? "
                + "UNION ALL SELECT 'f' || f.id || f.code || f.reason || f.created_at || COALESCE(f.resolved_at::text, '') FROM flag f WHERE f.tenant_id = ?) s",
                tenant, tenant, tenant, tenant);
    }

    @Test
    void retiring_overwrites_the_personal_data_deletes_nothing_and_leaves_the_books_as_they_were() {
        DemoWorld.Workspace w = DemoWorld.seeded();
        String books = books(w.tenant());
        String counts = DemoSeedIT.fingerprint(w.tenant());
        assertThat(ownerOne("SELECT count(*)::text FROM orders WHERE tenant_id = ? AND raw_text LIKE '%+999%'", w.tenant())).isNotEqualTo("0");
        assertThat(scalar(DB.demoOperatorDataSource(), "SELECT demo_retire(?)", w.tenant())).isEqualTo("t");
        assertThat(scalar(DB.demoOperatorDataSource(), "SELECT demo_retire(?)", w.tenant())).as("once").isEqualTo("f");
        assertThat(DemoSeedIT.fingerprint(w.tenant())).as("nothing deleted").isEqualTo(counts);
        assertThat(books(w.tenant())).as("ledger, audit log and flags unchanged").isEqualTo(books);
        assertThat(owner("SELECT DISTINCT raw_text || ' ' || COALESCE(delivery_note, '-') FROM orders WHERE tenant_id = ?", w.tenant()))
                .containsExactly("Removed when the demo ended. -");
        assertThat(owner("SELECT DISTINCT label FROM order_line WHERE tenant_id = ?", w.tenant())).containsExactly("Removed");
        assertThat(owner("SELECT DISTINCT display_name || ' ' || COALESCE(phone_hash, '-') || ' ' || COALESCE(phone_last4, '-') FROM customer WHERE tenant_id = ?",
                w.tenant())).containsExactly("Removed - -");
        assertThat(owner("SELECT DISTINCT raw_text || ' ' || COALESCE(sender_name, '-') || ' ' || COALESCE(sender_phone_hash, '-') || ' ' "
                + "|| COALESCE(sender_last4, '-') FROM payment WHERE tenant_id = ?", w.tenant())).containsExactly("Removed when the demo ended. - - -");
        assertThat(ownerOne("SELECT (t.disabled_at IS NOT NULL)::text || ' ' || d.state FROM tenant t JOIN demo_tenant d ON d.tenant_id = t.id WHERE t.id = ?",
                w.tenant())).isEqualTo("true RETIRED");
    }
}
