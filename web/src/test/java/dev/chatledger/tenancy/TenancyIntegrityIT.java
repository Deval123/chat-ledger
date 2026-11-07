package dev.chatledger.tenancy;

import dev.chatledger.ledger.LedgerDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static dev.chatledger.tenancy.TenantSeed.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V010: a row can never reference another tenant's row, and tenant and user
 * identifiers are well formed. Foreign-key checks bypass row-level security, so
 * these run as the schema owner to show the constraints themselves do the work.
 *
 * <p>Task 3.1.
 */
class TenancyIntegrityIT {

    private static final LedgerDatabase DB = LedgerDatabase.get();
    private static TenantSeed a;
    private static TenantSeed b;

    @BeforeAll
    static void seed() {
        a = TenantSeed.create(DB.migrateDataSource());
        b = TenantSeed.create(DB.migrateDataSource());
    }

    private interface Statementer {
        void run(Connection c) throws SQLException;
    }

    private static void expectViolation(String sqlState, String constraint, Statementer work) {
        assertThatThrownBy(() -> {
            try (Connection c = DB.migrateDataSource().getConnection()) {
                work.run(c);
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(sqlState);
            assertThat(e.getMessage()).contains(constraint);
        });
    }

    // ── Cross-tenant references are refused ─────────────────────────────────

    @Test
    void an_order_cannot_use_another_tenants_customer() {
        expectViolation("23503", "fk_orders_customer_same_tenant", c -> update(c,
                "INSERT INTO orders(id, tenant_id, external_id, customer_id, total_minor, currency, status, raw_text)"
                        + " VALUES (?, ?, ?, ?, 1, 'XAF', 'PENDING_PAYMENT', 'x')",
                UUID.randomUUID(), a.tenantId(), "x-" + UUID.randomUUID(), b.customerId()));
    }

    @Test
    void an_order_line_cannot_belong_to_another_tenants_order() {
        expectViolation("23503", "fk_order_line_order_same_tenant", c -> update(c,
                "INSERT INTO order_line(id, order_id, tenant_id, label, qty, unit_minor) VALUES (?, ?, ?, 'x', 1, 1)",
                UUID.randomUUID(), b.orderId(), a.tenantId()));
    }

    @Test
    void a_match_cannot_link_an_order_to_another_tenants_payment() {
        expectViolation("23503", "fk_match_payment_same_tenant", c -> update(c,
                "INSERT INTO match(id, tenant_id, order_id, payment_id, amount_minor, score, state) VALUES (?, ?, ?, ?, 1, 90, 'AUTO')",
                UUID.randomUUID(), a.tenantId(), a.orderId(), b.paymentId()));
    }

    @Test
    void a_match_cannot_link_another_tenants_order_to_a_payment() {
        expectViolation("23503", "fk_match_order_same_tenant", c -> update(c,
                "INSERT INTO match(id, tenant_id, order_id, payment_id, amount_minor, score, state) VALUES (?, ?, ?, ?, 1, 90, 'AUTO')",
                UUID.randomUUID(), a.tenantId(), b.orderId(), a.paymentId()));
    }

    @Test
    void a_match_cannot_be_decided_by_another_tenants_user() {
        expectViolation("23503", "fk_match_decided_by_same_tenant", c -> update(c,
                "INSERT INTO match(id, tenant_id, order_id, payment_id, amount_minor, score, state, decided_by)"
                        + " VALUES (?, ?, ?, ?, 1, 90, 'CONFIRMED', ?)",
                UUID.randomUUID(), a.tenantId(), a.orderId(), a.paymentId(), b.userId()));
    }

    @Test
    void an_audit_entry_cannot_name_another_tenants_user() {
        expectViolation("23503", "fk_audit_log_actor_same_tenant", c -> update(c,
                "INSERT INTO audit_log(id, tenant_id, actor, action, subject) VALUES (?, ?, ?, 'X', 'x')",
                UUID.randomUUID(), a.tenantId(), b.userId()));
    }

    @Test
    void the_same_references_inside_one_tenant_are_accepted() throws SQLException {
        // The seed itself links order, customer, payment, match, user and audit entry inside each tenant.
        try (Connection c = DB.migrateDataSource().getConnection()) {
            update(c, "INSERT INTO match(id, tenant_id, order_id, payment_id, amount_minor, score, state, decided_by)"
                            + " VALUES (?, ?, ?, ?, 1, 90, 'CONFIRMED', ?)",
                    UUID.randomUUID(), a.tenantId(), a.orderId(), a.paymentId(), a.userId());
        }
    }

    // ── Workspace code and email rules ──────────────────────────────────────

    @Test
    void a_workspace_code_must_be_well_formed() {
        for (String bad : new String[]{"Bad Slug", "UPPER", "ab", "-start", "end-", "has_underscore", "x".repeat(33)}) {
            expectViolation("23514", "chk_tenant_slug_format", c -> update(c,
                    "INSERT INTO tenant(id, name, currency, scale, slug) VALUES (?, 'n', 'XAF', 0, ?)",
                    UUID.randomUUID(), bad));
        }
    }

    @Test
    void reserved_workspace_codes_are_refused() {
        for (String reserved : new String[]{"admin", "api", "login", "logout", "static", "health"}) {
            expectViolation("23514", "chk_tenant_slug_format", c -> update(c,
                    "INSERT INTO tenant(id, name, currency, scale, slug) VALUES (?, 'n', 'XAF', 0, ?)",
                    UUID.randomUUID(), reserved));
        }
    }

    @Test
    void a_workspace_code_is_unique() {
        String existing = "seed-" + a.tenantId().toString().replace("-", "").substring(0, 12);
        expectViolation("23505", "uq_tenant_slug", c -> update(c,
                "INSERT INTO tenant(id, name, currency, scale, slug) VALUES (?, 'n', 'XAF', 0, ?)",
                UUID.randomUUID(), existing));
    }

    @Test
    void emails_are_stored_normalised() {
        for (String bad : new String[]{"Seller@Example.test", " seller@example.test", "seller@example.test ", "a@"}) {
            expectViolation("23514", "chk_app_user_email_normalised", c -> update(c,
                    "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'HELPER')",
                    UUID.randomUUID(), a.tenantId(), bad, "{placeholder}not-a-hash"));
        }
    }

    @Test
    void the_same_email_can_exist_in_two_tenants_but_not_twice_in_one() throws SQLException {
        String email = "shared-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        try (Connection c = DB.migrateDataSource().getConnection()) {
            update(c, "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'HELPER')",
                    UUID.randomUUID(), a.tenantId(), email, "{placeholder}not-a-hash");
            update(c, "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'HELPER')",
                    UUID.randomUUID(), b.tenantId(), email, "{placeholder}not-a-hash");
        }
        expectViolation("23505", "uq_user_email_per_tenant", c -> update(c,
                "INSERT INTO app_user(id, tenant_id, email, password_hash, role) VALUES (?, ?, ?, ?, 'HELPER')",
                UUID.randomUUID(), a.tenantId(), email, "{placeholder}not-a-hash"));
    }
}
