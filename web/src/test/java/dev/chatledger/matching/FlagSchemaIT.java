package dev.chatledger.matching;

import dev.chatledger.flags.FlagRepository;
import dev.chatledger.suspicion.Finding;
import dev.chatledger.suspicion.FlagCode;
import dev.chatledger.tenancy.TenantScope;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V019 in the database itself: the application role's privileges on {@code flag}, the write-once trigger (for the owner too), the shape of
 * each code, the bounds, the typed foreign keys and the uniqueness under concurrency.
 *
 * <p>Requirements: 5, 9.1. Task 7.
 */
class FlagSchemaIT {
    private final MatchWorld w = new MatchWorld();
    private final UUID order = w.order(5000, "PENDING_PAYMENT", "A-1", null);
    private final UUID payment = w.payment(5000, "RECEIVED");

    private interface Sql {
        void run(Connection c) throws SQLException;
    }

    private void asApp(Sql work) throws SQLException {
        new TenantScope(MatchWorld.DB.appDataSource()).call(w.tenant, c -> {
            work.run(c);
            return null;
        });
    }

    private void asOwner(Sql work) throws SQLException {
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                work.run(c);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private void insert(Connection c, String subject, UUID paymentId, UUID orderId, String code, String reason) throws SQLException {
        MatchWorld.run(c, "INSERT INTO flag(id, tenant_id, subject_type, payment_id, order_id, code, reason) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), w.tenant, subject, paymentId, orderId, code, reason);
    }

    private UUID openFlag() throws SQLException {
        UUID id = UUID.randomUUID();
        asApp(c -> MatchWorld.run(c, "INSERT INTO flag(id, tenant_id, subject_type, payment_id, code, reason) VALUES (?, ?, 'PAYMENT', ?, 'SENDER_BURST', 'x')",
                id, w.tenant, payment));
        return id;
    }

    private static boolean privilege(String sql, Object... args) throws SQLException {
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    // ── Privileges ─────────────────────────────────────────────────────────

    @Test
    void the_application_role_may_insert_and_update_only_the_resolution_and_may_not_delete() throws SQLException {
        assertThat(privilege("SELECT has_table_privilege('chatledger_app', 'public.flag', 'INSERT')")).isTrue();
        assertThat(privilege("SELECT has_table_privilege('chatledger_app', 'public.flag', 'SELECT')")).isTrue();
        for (String refused : new String[]{"UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
            assertThat(privilege("SELECT has_table_privilege('chatledger_app', 'public.flag', ?)", refused)).as(refused).isFalse();
        }
        List<String> updatable = new ArrayList<>();
        try (Connection c = MatchWorld.DB.migrateDataSource().getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'flag' ORDER BY column_name")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (privilege("SELECT has_column_privilege('chatledger_app', 'public.flag', ?, 'UPDATE')", rs.getString(1))) {
                        updatable.add(rs.getString(1));
                    }
                }
            }
        }
        assertThat(updatable).containsExactly("resolved_at", "resolved_by");
        UUID flag = openFlag();
        assertThatThrownBy(() -> asApp(c -> MatchWorld.run(c, "UPDATE flag SET code = 'DUPLICATE_TX' WHERE id = ?", flag))).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asApp(c -> MatchWorld.run(c, "DELETE FROM flag WHERE id = ?", flag))).hasMessageContaining("permission denied");
    }

    // ── Write-once ─────────────────────────────────────────────────────────

    @Test
    void a_flag_is_resolved_once_and_never_changed_or_removed_even_by_the_owner() throws SQLException {
        UUID flag = openFlag();
        assertThatThrownBy(() -> asApp(c -> MatchWorld.run(c, "UPDATE flag SET resolved_by = ? WHERE id = ?", w.user, flag)))
                .as("a resolver without a time").isInstanceOf(SQLException.class);
        asApp(c -> MatchWorld.run(c, "UPDATE flag SET resolved_at = now(), resolved_by = ? WHERE id = ?", w.user, flag));
        assertThatThrownBy(() -> asApp(c -> MatchWorld.run(c, "UPDATE flag SET resolved_at = now() + interval '1 minute' WHERE id = ?", flag)))
                .hasMessageContaining("already resolved");
        assertThatThrownBy(() -> asApp(c -> MatchWorld.run(c, "UPDATE flag SET resolved_at = NULL, resolved_by = NULL WHERE id = ?", flag)))
                .hasMessageContaining("already resolved");
        UUID other = openFlag2();
        assertThatThrownBy(() -> asOwner(c -> MatchWorld.run(c, "UPDATE flag SET code = 'DUPLICATE_TX' WHERE id = ?", other))).hasMessageContaining("must resolve it");
        assertThatThrownBy(() -> asOwner(c -> MatchWorld.run(c, "UPDATE flag SET reason = 'other', resolved_at = now(), resolved_by = ? WHERE id = ?", w.user, other)))
                .hasMessageContaining("only the resolution");
        assertThatThrownBy(() -> asOwner(c -> MatchWorld.run(c, "UPDATE flag SET order_id = ?, resolved_at = now(), resolved_by = ? WHERE id = ?", order, w.user, other)))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> asOwner(c -> MatchWorld.run(c, "DELETE FROM flag WHERE id = ?", flag))).hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> asOwner(c -> MatchWorld.run(c, "TRUNCATE flag"))).hasMessageContaining("cannot be deleted or truncated");
        assertThat(MatchingFixture.rows("SELECT code || ' ' || (resolved_at IS NOT NULL) FROM flag WHERE tenant_id = ? ORDER BY 1", w.tenant))
                .containsExactly("NO_PROVIDER_ID false", "SENDER_BURST true");
    }

    private UUID openFlag2() throws SQLException {
        UUID id = UUID.randomUUID();
        asApp(c -> MatchWorld.run(c, "INSERT INTO flag(id, tenant_id, subject_type, payment_id, code, reason) VALUES (?, ?, 'PAYMENT', ?, 'NO_PROVIDER_ID', 'x')",
                id, w.tenant, payment));
        return id;
    }

    @Test
    void a_resolver_without_a_time_or_a_time_without_a_resolver_is_refused_by_the_check_itself() {
        // Inserted (no trigger runs on insert), so only chk_flag_resolution stands in the way.
        assertThatThrownBy(() -> asOwner(c -> MatchWorld.run(c, "INSERT INTO flag(id, tenant_id, subject_type, payment_id, code, reason, resolved_by) "
                + "VALUES (?, ?, 'PAYMENT', ?, 'SENDER_BURST', 'x', ?)", UUID.randomUUID(), w.tenant, payment, w.user)))
                .hasMessageContaining("chk_flag_resolution");
        assertThatThrownBy(() -> asOwner(c -> MatchWorld.run(c, "INSERT INTO flag(id, tenant_id, subject_type, payment_id, code, reason, resolved_at) "
                + "VALUES (?, ?, 'PAYMENT', ?, 'SENDER_BURST', 'x', now())", UUID.randomUUID(), w.tenant, payment)))
                .hasMessageContaining("chk_flag_resolution");
    }

    @Test
    void the_order_of_a_pair_flag_cannot_be_changed_even_while_resolving_it() throws SQLException {
        UUID otherOrder = w.order(5000, "PENDING_PAYMENT", "A-2", null);
        UUID flag = UUID.randomUUID();
        asApp(c -> MatchWorld.run(c, "INSERT INTO flag(id, tenant_id, subject_type, payment_id, order_id, code, reason) VALUES (?, ?, 'PAYMENT', ?, ?, 'PHONE_MISMATCH', 'x')",
                flag, w.tenant, payment, order));
        // Every CHECK and foreign key accepts the new order; only the trigger refuses.
        assertThatThrownBy(() -> asOwner(c -> MatchWorld.run(c, "UPDATE flag SET order_id = ?, resolved_at = now(), resolved_by = ? WHERE id = ?", otherOrder, w.user, flag)))
                .hasMessageContaining("only the resolution");
        assertThat(MatchingFixture.rows("SELECT order_id::text || ' ' || (resolved_at IS NULL) FROM flag WHERE id = ?", flag)).containsExactly(order + " true");
    }

    @Test
    void the_resolver_must_be_a_user_of_the_same_tenant_and_the_time_not_before_the_flag() throws SQLException {
        UUID flag = openFlag();
        UUID stranger = new MatchWorld().user;
        assertThatThrownBy(() -> asOwner(c -> MatchWorld.run(c, "UPDATE flag SET resolved_at = now(), resolved_by = ? WHERE id = ?", stranger, flag)))
                .hasMessageContaining("fk_flag_resolver_same_tenant");
        assertThatThrownBy(() -> asOwner(c -> MatchWorld.run(c, "UPDATE flag SET resolved_at = created_at - interval '1 second', resolved_by = ? WHERE id = ?", w.user, flag)))
                .hasMessageContaining("chk_flag_resolution");
    }

    // ── Shapes ─────────────────────────────────────────────────────────────

    @Test
    void each_code_has_its_shape_and_anything_else_is_refused() throws SQLException {
        // Accepted, one of each shape.
        asOwner(c -> {
            insert(c, "PAYMENT", payment, null, "DUPLICATE_TX", "x");
            insert(c, "PAYMENT", payment, order, "OVERPAYMENT", "x");
            insert(c, "ORDER", null, order, "SENDER_ALIAS", "x");
            insert(c, "PAYMENT", payment, null, "SENDER_ALIAS", "x");
        });
        record Bad(String subject, UUID payment, UUID order, String code, String reason, String constraint) { }
        for (Bad bad : List.of(
                new Bad("PAYMENT", payment, order, "DUPLICATE_TX", "x", "chk_flag_code_shape"),          // a payment code naming an order
                new Bad("PAYMENT", payment, null, "PHONE_MISMATCH", "x", "chk_flag_code_shape"),         // a pair code without its order
                new Bad("ORDER", null, order, "OVERPAYMENT", "x", "chk_flag_code_shape"),                // a pair code on an order
                new Bad("PAYMENT", payment, order, "SENDER_ALIAS", "x", "chk_flag_code_shape"),          // R10 is never about a pair
                new Bad("PAYMENT", payment, null, "SHORT_OF_ROUND", "x", "chk_flag_code_shape"),         // the dropped rule
                new Bad("PAYMENT", payment, null, "PROBE", "x", "chk_flag_code_shape"),
                new Bad("PAYMENT", null, null, "SENDER_BURST", "x", "chk_flag_subject"),                 // a payment flag without its payment
                new Bad("ORDER", payment, order, "SENDER_ALIAS", "x", "chk_flag_subject"),               // an order flag naming a payment
                new Bad("ORDER", null, null, "SENDER_ALIAS", "x", "chk_flag_subject"),
                new Bad("MATCH", payment, null, "SENDER_BURST", "x", "violates check constraint \"chk_flag_"),   // refused by both shape checks
                new Bad("PAYMENT", payment, null, "SENDER_BURST", "", "chk_flag_reason"),
                new Bad("PAYMENT", payment, null, "SENDER_BURST", "x".repeat(301), "chk_flag_reason"))) {
            assertThatThrownBy(() -> asOwner(c -> insert(c, bad.subject, bad.payment, bad.order, bad.code, bad.reason))).as(bad.toString())
                    .hasMessageContaining(bad.constraint);
        }
        asOwner(c -> insert(c, "PAYMENT", payment, null, "SENDER_BURST", "x".repeat(300)));
    }

    @Test
    void a_flag_cannot_name_a_record_that_does_not_exist_or_belongs_to_another_tenant() {
        MatchWorld other = new MatchWorld();
        UUID theirs = other.payment(100, "RECEIVED");
        UUID theirOrder = other.order(100, "PENDING_PAYMENT", null, null);
        assertThatThrownBy(() -> asOwner(c -> insert(c, "PAYMENT", theirs, null, "SENDER_BURST", "x"))).hasMessageContaining("fk_flag_payment_same_tenant");
        assertThatThrownBy(() -> asOwner(c -> insert(c, "PAYMENT", UUID.randomUUID(), null, "SENDER_BURST", "x"))).hasMessageContaining("fk_flag_payment_same_tenant");
        assertThatThrownBy(() -> asOwner(c -> insert(c, "ORDER", null, theirOrder, "SENDER_ALIAS", "x"))).hasMessageContaining("fk_flag_order_same_tenant");
        assertThatThrownBy(() -> asOwner(c -> insert(c, "PAYMENT", payment, theirOrder, "PHONE_MISMATCH", "x"))).hasMessageContaining("fk_flag_order_same_tenant");
    }

    // ── One flag per rule and subject ──────────────────────────────────────

    @Test
    void one_flag_per_rule_and_subject_even_when_sixteen_write_it_at_once_and_the_pair_is_part_of_the_key() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        FlagRepository flags = new FlagRepository();
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<Boolean> task = () -> {
                    start.await();
                    return new TenantScope(MatchingFixture.POOL).call(w.tenant,
                            c -> flags.insertWithin(c, w.tenant, Finding.pair(FlagCode.PHONE_MISMATCH, payment, order), Instant.now()));
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            int written = 0;
            for (Future<Boolean> r : results) {
                written += r.get() ? 1 : 0;
            }
            assertThat(written).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        UUID otherOrder = w.order(5000, "PENDING_PAYMENT", "A-2", null);
        new TenantScope(MatchWorld.DB.appDataSource()).call(w.tenant, c -> {
            assertThat(flags.insertWithin(c, w.tenant, Finding.pair(FlagCode.PHONE_MISMATCH, payment, otherOrder), Instant.now())).as("another pair").isTrue();
            assertThat(flags.insertWithin(c, w.tenant, Finding.payment(FlagCode.SENDER_BURST, payment), Instant.now())).as("the payment alone").isTrue();
            assertThat(flags.insertWithin(c, w.tenant, Finding.order(FlagCode.SENDER_ALIAS, order), Instant.now())).isTrue();
            assertThat(flags.insertWithin(c, w.tenant, Finding.payment(FlagCode.SENDER_ALIAS, payment), Instant.now())).as("R10 on the payment is not R10 on the order").isTrue();
            assertThat(flags.insertWithin(c, w.tenant, Finding.order(FlagCode.SENDER_ALIAS, order), Instant.now())).isFalse();
            return null;
        });
        assertThat(MatchingFixture.count("SELECT count(*) FROM flag WHERE tenant_id = ?", w.tenant, null)).isEqualTo(5);
    }
}
