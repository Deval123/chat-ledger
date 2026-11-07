package dev.chatledger.orders;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the database itself refuses (V014), whoever sends the row, and what the application role may and may not do with
 * the order tables. The constraints run as the schema owner so a refusal is the constraint and not a privilege.
 *
 * <p>Requirements: 1, 9. Task 4.
 */
class OrderConstraintsIT {

    private static final IngestionFixture F = new IngestionFixture();
    private final UUID tenant = F.tenant();

    private void order(String status, Long total, String currency, String externalId, String rawText, String ref, String note)
            throws SQLException {
        try (Connection c = IngestionFixture.DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO orders(id, tenant_id, external_id, order_ref, total_minor, currency, status, raw_text, delivery_note) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            ps.setString(3, externalId);
            ps.setString(4, ref);
            if (total == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, total);
            }
            ps.setString(6, currency);
            ps.setString(7, status);
            ps.setString(8, rawText);
            ps.setString(9, note);
            ps.executeUpdate();
        }
    }

    private void order(String status, Long total) throws SQLException {
        order(status, total, "XAF", "ext-" + UUID.randomUUID(), "raw", null, null);
    }

    private static void refuses(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String state, String constraint) {
        assertThatThrownBy(call).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo(state);
            if (constraint != null) {
                assertThat(e.getMessage()).contains(constraint);
            }
        });
    }

    // ── total_minor, tied to the status ────────────────────────────────────

    @Test
    void total_is_null_only_for_unparsed_and_needs_review() throws SQLException {
        order("UNPARSED", null);
        order("NEEDS_REVIEW", null);
        order("NEEDS_REVIEW", 500L);
        order("PENDING_PAYMENT", 500L);
        order("PAID", 500L);
        order("CANCELLED", 500L);
        for (String status : new String[]{"PENDING_PAYMENT", "PARTIALLY_PAID", "PAID", "CANCELLED"}) {
            refuses(() -> order(status, null), "23514", "chk_orders_total_by_status");
        }
    }

    @Test
    void an_unparsed_order_never_has_a_total_and_no_total_is_zero_negative_or_over_the_cap() {
        refuses(() -> order("UNPARSED", 500L), "23514", "chk_orders_total_by_status");
        for (long bad : new long[]{0, -1, 1_000_000_000_001L, Long.MAX_VALUE}) {
            refuses(() -> order("PENDING_PAYMENT", bad), "23514", "chk_orders_total_by_status");
            refuses(() -> order("NEEDS_REVIEW", bad), "23514", "chk_orders_total_by_status");
        }
    }

    @Test
    void currency_is_never_null() {
        refuses(() -> order("UNPARSED", null, null, "ext-" + UUID.randomUUID(), "raw", null, null), "23502", null);
        // CHAR(3) pads "XA" to "XA ", so only the format check stops it.
        for (String bad : new String[]{"XA", "xaf", "X1F", "XAF1", " XA", "\u00C9UR"}) {
            refuses(() -> order("NEEDS_REVIEW", null, bad, "ext-" + UUID.randomUUID(), "raw", null, null), bad.length() > 3 ? "22001" : "23514", null);
        }
    }

    // ── Text columns ───────────────────────────────────────────────────────

    @Test
    void external_ids_follow_the_format() throws SQLException {
        order("UNPARSED", null, "XAF", "a".repeat(128), "raw", null, null);
        order("UNPARSED", null, "XAF", "A-1.b_2:c", "raw", null, null);
        for (String bad : new String[]{"a".repeat(129), "", "has space", "semi;colon", "quote'", "new\nline", "caf\u00E9", "../x"}) {
            refuses(() -> order("UNPARSED", null, "XAF", bad, "raw", null, null), "23514", "chk_orders_external_id_format");
        }
    }

    @Test
    void raw_text_is_one_to_four_thousand_characters_without_control_characters() throws SQLException {
        order("UNPARSED", null, "XAF", "ext-" + UUID.randomUUID(), "line one\nline\ttwo\r\n \u00E9 \uD83D\uDE00 \u202E", null, null);
        order("UNPARSED", null, "XAF", "ext-" + UUID.randomUUID(), "a".repeat(4000), null, null);
        for (String bad : new String[]{"", "a".repeat(4001), "a\u0001b", "a\u0007b", "a\u001Bb", "a\u007Fb", "a\u0085b", "a\u009Fb"}) {
            refuses(() -> order("UNPARSED", null, "XAF", "ext-" + UUID.randomUUID(), bad, null, null), "23514", "chk_orders_raw_text");
        }
        // PostgreSQL text cannot hold NUL at all: the driver or server refuses it before any constraint.
        assertThatThrownBy(() -> order("UNPARSED", null, "XAF", "ext-" + UUID.randomUUID(), "a\u0000b", null, null))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void reference_and_delivery_note_rules() throws SQLException {
        order("UNPARSED", null, "XAF", "ext-" + UUID.randomUUID(), "raw", "A-1:2.b_c", "gate");
        for (String badRef : new String[]{"a b", "", "a".repeat(65), "x;y", "../etc"}) {
            refuses(() -> order("UNPARSED", null, "XAF", "ext-" + UUID.randomUUID(), "raw", badRef, null), "23514", "chk_orders_ref");
        }
        for (String badNote : new String[]{"", "a".repeat(301)}) {
            refuses(() -> order("UNPARSED", null, "XAF", "ext-" + UUID.randomUUID(), "raw", null, badNote), "23514", "chk_orders_delivery_note");
        }
    }

    @Test
    void the_unique_external_id_per_tenant_still_holds() throws SQLException {
        order("UNPARSED", null, "XAF", "same", "raw", null, null);
        refuses(() -> order("UNPARSED", null, "XAF", "same", "raw", null, null), "23505", "uq_order_external_per_tenant");
    }

    // ── Lines, customers, catalog ──────────────────────────────────────────

    private UUID anOrder() throws SQLException {
        String ext = "ext-" + UUID.randomUUID();
        order("PENDING_PAYMENT", 500L, "XAF", ext, "raw", null, null);
        try (Connection c = IngestionFixture.DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM orders WHERE tenant_id = ? AND external_id = ?")) {
            ps.setObject(1, tenant);
            ps.setString(2, ext);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private void line(UUID orderId, String label, int qty, Long unit) throws SQLException {
        try (Connection c = IngestionFixture.DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO order_line(id, order_id, tenant_id, label, qty, unit_minor) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, orderId);
            ps.setObject(3, tenant);
            ps.setString(4, label);
            ps.setInt(5, qty);
            if (unit == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, unit);
            }
            ps.executeUpdate();
        }
    }

    @Test
    void order_lines_have_a_label_a_quantity_in_range_and_a_price_that_is_null_or_positive() throws SQLException {
        UUID order = anOrder();
        line(order, "rice", 1, 500L);
        line(order, "mystery", 9999, null);
        line(order, "a".repeat(100), 1, 1_000_000_000_000L);
        refuses(() -> line(order, "x", 1, 0L), "23514", "chk_order_line_unit");
        refuses(() -> line(order, "x", 1, -5L), "23514", "chk_order_line_unit");
        refuses(() -> line(order, "x", 1, 1_000_000_000_001L), "23514", "chk_order_line_unit");
        refuses(() -> line(order, "", 1, 5L), "23514", "chk_order_line_label");
        refuses(() -> line(order, "a".repeat(101), 1, 5L), "23514", "chk_order_line_label");
        refuses(() -> line(order, "x", 10_000, 5L), "23514", "chk_order_line_qty_max");
        refuses(() -> line(order, "x", 0, 5L), "23514", null);
    }

    private void customer(String name, String hash, String last4) throws SQLException {
        try (Connection c = IngestionFixture.DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO customer(id, tenant_id, display_name, phone_hash, phone_last4) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            ps.setString(3, name);
            ps.setString(4, hash);
            ps.setString(5, last4);
            ps.executeUpdate();
        }
    }

    @Test
    void customers_store_only_a_hash_in_hex_with_the_last_four_digits() throws SQLException {
        customer("Ana", null, null);
        customer("Ana", "a".repeat(64), "1234");
        refuses(() -> customer("Ana", "A".repeat(64), "1234"), "23514", "chk_customer_phone_hash");
        refuses(() -> customer("Ana", "a".repeat(63), "1234"), "23514", "chk_customer_phone_hash");
        refuses(() -> customer("Ana", "9990000001234", "1234"), "23514", "chk_customer_phone_hash");
        refuses(() -> customer("Ana", "b".repeat(64), "12ab"), "23514", "chk_customer_phone_last4");
        refuses(() -> customer("Ana", "c".repeat(64), null), "23514", "chk_customer_phone_pair");
        refuses(() -> customer("Ana", null, "1234"), "23514", "chk_customer_phone_pair");
        refuses(() -> customer("", null, null), "23514", "chk_customer_name");
        refuses(() -> customer("a".repeat(101), null, null), "23514", "chk_customer_name");
        refuses(() -> customer("Ana", "a".repeat(64), "1234"), "23505", "uq_customer_phone_per_tenant");
    }

    private void catalog(String key, String name, long price) throws SQLException {
        try (Connection c = IngestionFixture.DB.migrateDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO catalog_item(id, tenant_id, name_key, display_name, unit_minor) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            ps.setString(3, key);
            ps.setString(4, name);
            ps.setLong(5, price);
            ps.executeUpdate();
        }
    }

    @Test
    void catalog_items_are_unique_per_tenant_and_have_a_positive_capped_price() throws SQLException {
        catalog("rice", "Rice", 1500);
        refuses(() -> catalog("rice", "Rice again", 1500), "23505", "uq_catalog_item_key_per_tenant");
        refuses(() -> catalog("a", "A", 0), "23514", "chk_catalog_item_price");
        refuses(() -> catalog("b", "B", -1), "23514", "chk_catalog_item_price");
        refuses(() -> catalog("c", "C", 1_000_000_000_001L), "23514", "chk_catalog_item_price");
        refuses(() -> catalog("", "D", 5), "23514", "chk_catalog_item_key");
        refuses(() -> catalog("e", "", 5), "23514", "chk_catalog_item_name");
        refuses(() -> catalog("f".repeat(101), "F", 5), "23514", "chk_catalog_item_key");
    }

    // ── What the application role may do ───────────────────────────────────

    private void asApp(String sql) throws SQLException {
        try (Connection c = IngestionFixture.DB.appDataSource().getConnection(); var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void the_application_role_can_insert_orders_through_the_service_but_cannot_update_or_delete_them() throws SQLException {
        assertThat(F.ingest(tenant, IngestionFixture.id(), "Name: Ana\nPhone: +999 000 000 1234\n1 x rice @ 5").stored()).isTrue();
        for (String table : new String[]{"orders", "order_line", "customer"}) {
            for (String sql : new String[]{"UPDATE " + table + " SET id = id", "DELETE FROM " + table}) {
                assertThatThrownBy(() -> asApp(sql)).as(sql).isInstanceOfSatisfying(SQLException.class, e -> {
                    assertThat(e.getSQLState()).isEqualTo("42501");
                    assertThat(e.getMessage()).contains("permission denied for table " + table);
                });
            }
        }
    }

    @Test
    void the_application_role_cannot_write_the_catalog() {
        for (String sql : new String[]{
                "INSERT INTO catalog_item(id, tenant_id, name_key, display_name, unit_minor) VALUES (gen_random_uuid(), gen_random_uuid(), 'k', 'K', 5)",
                "UPDATE catalog_item SET unit_minor = 1", "DELETE FROM catalog_item"}) {
            assertThatThrownBy(() -> asApp(sql)).as(sql).isInstanceOfSatisfying(SQLException.class, e -> {
                assertThat(e.getSQLState()).isEqualTo("42501");
                assertThat(e.getMessage()).contains("permission denied for table catalog_item");
            });
        }
    }

    @Test
    void the_provisioner_login_has_no_privilege_on_the_new_tables() {
        for (String table : new String[]{"catalog_item", "orders", "order_line", "customer"}) {
            assertThatThrownBy(() -> {
                try (Connection c = IngestionFixture.DB.provisionerDataSource().getConnection(); var s = c.createStatement()) {
                    s.execute("SELECT 1 FROM " + table);
                }
            }).as(table).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
    }
}
