package dev.chatledger.matching;

import dev.chatledger.tenancy.TenantScope;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The four accounts matching posts to: created once per tenant whatever the number of callers, never shared between tenants, and refused
 * (nothing posted) if a tenant's account with one of the codes has the wrong type.
 *
 * <p>Requirements: 4.1, 4.3. Task 6.1.
 */
class ChartOfAccountsIT {

    private final TenantScope scope = new TenantScope(MatchWorld.DB.appDataSource());

    private ChartOfAccounts.Accounts ensure(UUID tenant) throws SQLException {
        return scope.call(tenant, c -> ChartOfAccounts.ensure(c, tenant));
    }

    private Map<String, String> accountsOf(UUID tenant) throws SQLException {
        return scope.call(tenant, c -> {
            Map<String, String> out = new java.util.TreeMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT code, name || '|' || type FROM account WHERE tenant_id = ?")) {
                ps.setObject(1, tenant);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return out;
        });
    }

    @Test
    void the_four_accounts_are_created_with_their_codes_names_and_types() throws SQLException {
        UUID tenant = MatchWorld.DB.createTenant();
        ChartOfAccounts.Accounts a = ensure(tenant);
        assertThat(Set.of(a.clearing(), a.receivable(), a.customerCredit(), a.revenue())).hasSize(4);
        assertThat(accountsOf(tenant)).containsExactly(
                Map.entry("1000", "Mobile money clearing|ASSET"), Map.entry("1100", "Accounts receivable|ASSET"),
                Map.entry("2100", "Customer credit|LIABILITY"), Map.entry("4000", "Sales revenue|REVENUE"));
    }

    @Test
    void asking_again_changes_nothing() throws SQLException {
        UUID tenant = MatchWorld.DB.createTenant();
        ChartOfAccounts.Accounts first = ensure(tenant);
        assertThat(ensure(tenant)).isEqualTo(first);
        assertThat(accountsOf(tenant)).hasSize(4);
    }

    @Test
    void sixteen_callers_at_once_make_four_accounts_and_all_get_the_same_ids() throws Exception {
        UUID tenant = MatchWorld.DB.createTenant();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ChartOfAccounts.Accounts>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return ensure(tenant);
            }));
        }
        start.countDown();
        Set<ChartOfAccounts.Accounts> seen = new HashSet<>();
        for (Future<ChartOfAccounts.Accounts> f : futures) {
            seen.add(f.get());
        }
        pool.shutdown();
        assertThat(seen).hasSize(1);
        assertThat(accountsOf(tenant)).hasSize(4);
    }

    @Test
    void tenants_never_share_an_account() throws SQLException {
        UUID a = MatchWorld.DB.createTenant();
        UUID b = MatchWorld.DB.createTenant();
        ChartOfAccounts.Accounts forA = ensure(a);
        ChartOfAccounts.Accounts forB = ensure(b);
        assertThat(Set.of(forA.clearing(), forA.receivable(), forA.customerCredit(), forA.revenue(),
                forB.clearing(), forB.receivable(), forB.customerCredit(), forB.revenue())).hasSize(8);
        assertThat(accountsOf(a)).hasSize(4);
        assertThat(accountsOf(b)).hasSize(4);
    }

    @Test
    void an_account_with_one_of_the_codes_but_another_type_makes_the_chart_unusable() {
        UUID tenant = MatchWorld.DB.createTenant();
        try (var c = MatchWorld.DB.migrateDataSource().getConnection(); var ps = c.prepareStatement(
                "INSERT INTO account(id, tenant_id, code, name, type) VALUES (?, ?, '1100', 'Something else', 'LIABILITY')")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThatThrownBy(() -> ensure(tenant)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("1100").hasMessageNotContaining(tenant.toString());
    }
}
