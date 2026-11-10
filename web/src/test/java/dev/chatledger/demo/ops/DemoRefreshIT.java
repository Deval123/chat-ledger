package dev.chatledger.demo.ops;

import dev.chatledger.ledger.LedgerDatabase;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.chatledger.demo.ops.DemoWorld.DB;
import static dev.chatledger.demo.ops.DemoWorld.ownerOne;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refresh runner (task 9, D6, D24): one pass retires what is due (ended leases, stale ready workspaces, stuck seeds) and tops the
 * pool up to its size; a second runner finds the lock held and does nothing; the command runs once or in a loop, and refuses to make a
 * database with a real workspace a demo. All through the V020 functions on the demo operator's login.
 *
 * <p>Requirement 7. Task 9.
 */
class DemoRefreshIT {

    private static long waiting(String state) {
        return Long.parseLong(ownerOne("SELECT count(*)::text FROM demo_tenant WHERE state = ?", state));
    }

    private static void forced(String sql, Object... args) throws SQLException {
        try (Connection c = DB.superDataSource().getConnection()) {
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

    @Test
    void a_pass_tops_the_pool_up_to_its_size_and_the_next_pass_has_nothing_to_do() {
        DemoWorld.retireEverythingWaiting();
        DemoRefresh.Report first = DemoWorld.refresh().run(10);
        assertThat(first).isEqualTo(new DemoRefresh.Report(false, 0, 10, 0));
        assertThat(waiting("READY")).isEqualTo(10);
        assertThat(waiting("SEEDING")).isZero();
        DemoRefresh.Report second = DemoWorld.refresh().run(10);
        assertThat(second).isEqualTo(new DemoRefresh.Report(false, 0, 0, 0));
        DemoWorld.retireEverythingWaiting();
    }

    @Test
    void a_pass_retires_ended_leases_stale_ready_workspaces_and_stuck_seeds_then_replaces_them() throws SQLException {
        DemoWorld.retireEverythingWaiting();
        DemoWorld.Workspace leased = DemoWorld.seeded();
        assertThat(DemoWorld.claim()).isEqualTo(leased);
        DemoWorld.Workspace stale = DemoWorld.seeded();
        DemoWorld.Workspace stuck = DemoWorld.created();
        DemoWorld.Workspace fresh = DemoWorld.seeded();
        forced("UPDATE demo_tenant SET lease_ends_at = now() - interval '1 second', claimed_at = now() - interval '31 minutes' WHERE tenant_id = ?",
                leased.tenant());
        forced("UPDATE demo_tenant SET ready_at = now() - interval '3 hours' WHERE tenant_id = ?", stale.tenant());
        forced("UPDATE demo_tenant SET created_at = now() - interval '10 minutes' WHERE tenant_id = ?", stuck.tenant());
        DemoRefresh.Report report = DemoWorld.refresh().run(10);
        assertThat(report.retired()).isEqualTo(3);
        assertThat(report.created()).as("topped up to ten, the fresh one counted").isEqualTo(9);
        for (DemoWorld.Workspace w : new DemoWorld.Workspace[]{leased, stale, stuck}) {
            assertThat(ownerOne("SELECT d.state || ' ' || (t.disabled_at IS NOT NULL)::text FROM demo_tenant d JOIN tenant t ON t.id = d.tenant_id "
                    + "WHERE d.tenant_id = ?", w.tenant())).isEqualTo("RETIRED true");
        }
        assertThat(ownerOne("SELECT state FROM demo_tenant WHERE tenant_id = ?", fresh.tenant())).isEqualTo("READY");
        DemoWorld.retireEverythingWaiting();
    }

    @Test
    void a_second_runner_finds_the_lock_held_and_does_nothing() throws SQLException {
        DemoWorld.retireEverythingWaiting();
        try (Connection holder = DB.demoOperatorDataSource().getConnection();
             PreparedStatement ps = holder.prepareStatement("SELECT demo_refresh_lock()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getBoolean(1)).isTrue();
            assertThat(DemoWorld.refresh().run(10)).isEqualTo(new DemoRefresh.Report(true, 0, 0, 0));
            assertThat(waiting("READY")).isZero();
        }
        // The lock went with its connection.
        assertThat(DemoWorld.refresh().run(10).created()).isEqualTo(10);
        DemoWorld.retireEverythingWaiting();
    }

    @Test
    void a_seed_that_wrote_too_little_is_never_marked_ready_and_the_pass_says_it_failed() {
        DemoWorld.retireEverythingWaiting();
        DemoCommand.Services s = DemoWorld.services();
        DemoSeeder nothing = new DemoSeeder(s.orders(), s.payments(), s.matcher(), s.board(), s.messages()) {
            @Override
            public void seed(java.util.UUID tenantId, java.util.UUID sellerId) {
                // writes nothing, as a seed cut short would
            }
        };
        DemoRefresh.Report report = new DemoRefresh(DB.demoOperatorDataSource(), nothing, dev.chatledger.security.PasswordHashing.create()).run(10);
        assertThat(report.created()).isZero();
        assertThat(report.failed()).isEqualTo(1);
        assertThat(waiting("READY")).isZero();
        assertThat(waiting("SEEDING")).as("left for a later pass to retire").isEqualTo(1);
        DemoWorld.retireEverythingWaiting();
    }

    // ── The command ────────────────────────────────────────────────────────

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private static Map<String, String> env(LedgerDatabase db) {
        Map<String, String> env = new HashMap<>();
        env.put("DB_URL", db.jdbcUrl());
        env.put("DB_USER", LedgerDatabase.APP_USER);
        env.put("DB_PASSWORD", db.appPassword());
        env.put("DEMO_DB_USER", LedgerDatabase.DEMO_USER);
        env.put("DEMO_DB_PASSWORD", db.demoOperatorPassword());
        env.put("APP_SECRET", DemoWorld.SECRET);
        return env;
    }

    private int command(Map<String, String> env, DemoCommand.Sleeper sleeper, String... args) {
        DemoCommand.Databases real = (url, user, password) -> {
            org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
            ds.setUrl(url);
            ds.setUser(user);
            ds.setPassword(password);
            return ds;
        };
        return new DemoCommand(env, real, sleeper, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
    }

    @Test
    void the_command_refuses_to_make_a_database_with_a_real_workspace_a_demo_and_runs_once_or_in_a_loop() {
        LedgerDatabase.get().createTenant();
        assertThat(command(env(LedgerDatabase.get()), d -> false, "demo-init")).isEqualTo(DemoCommand.REFUSED);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("holds a real workspace");
        assertThat(command(env(DB), d -> false, "demo-init")).isEqualTo(DemoCommand.OK);
        DemoWorld.retireEverythingWaiting();
        assertThat(command(env(DB), d -> false, "demo-refresh", "--pool", "10")).isEqualTo(DemoCommand.OK);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("created 10");
        assertThat(waiting("READY")).isEqualTo(10);
        AtomicInteger passes = new AtomicInteger();
        assertThat(command(env(DB), d -> {
            assertThat(d).hasSeconds(60);
            return passes.incrementAndGet() < 3;
        }, "demo-refresh", "--pool", "10", "--every", "60s")).isEqualTo(DemoCommand.OK);
        assertThat(passes.get()).as("three passes, then stopped").isEqualTo(3);
        assertThat(out.toString(StandardCharsets.UTF_8).split("created 0", -1)).as("each later pass had nothing to add").hasSize(4);
        String printed = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
        assertThat(printed).doesNotContain(DB.appPassword()).doesNotContain(DB.demoOperatorPassword()).doesNotContain(DemoWorld.SECRET);
        DemoWorld.retireEverythingWaiting();
    }
}
