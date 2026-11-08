package dev.chatledger.matching;

import dev.chatledger.ledger.AccountType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The four accounts every tenant's matching posts to, with fixed codes: created idempotently on the caller's connection (so it is part
 * of the caller's transaction) and read back. The codes are also written into the V016 consistency trigger; a test keeps the two in step.
 *
 * <p>If an account with one of these codes already exists with a different type, nothing is posted: the chart is wrong and the safe
 * failure is to refuse, not to post to it.
 */
public final class ChartOfAccounts {

    public static final String CLEARING_CODE = "1000";
    public static final String RECEIVABLE_CODE = "1100";
    public static final String CUSTOMER_CREDIT_CODE = "2100";
    public static final String REVENUE_CODE = "4000";

    /** The ids of the four accounts of one tenant. */
    public record Accounts(UUID clearing, UUID receivable, UUID customerCredit, UUID revenue) {
    }

    private record Spec(String code, String name, AccountType type) {
    }

    private static final Spec[] SPECS = {
            new Spec(CLEARING_CODE, "Mobile money clearing", AccountType.ASSET),
            new Spec(RECEIVABLE_CODE, "Accounts receivable", AccountType.ASSET),
            new Spec(CUSTOMER_CREDIT_CODE, "Customer credit", AccountType.LIABILITY),
            new Spec(REVENUE_CODE, "Sales revenue", AccountType.REVENUE)};

    private ChartOfAccounts() {
    }

    /** Creates the missing accounts of the tenant and returns the four ids. Runs on a connection already scoped to the tenant. */
    public static Accounts ensure(Connection c, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO account(id, tenant_id, code, name, type) VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?), (?, ?, ?, ?, ?), (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, code) DO NOTHING")) {
            int at = 1;
            for (Spec spec : SPECS) {
                ps.setObject(at++, UUID.randomUUID());
                ps.setObject(at++, tenantId);
                ps.setString(at++, spec.code());
                ps.setString(at++, spec.name());
                ps.setString(at++, spec.type().name());
            }
            ps.executeUpdate();
        }
        Map<String, UUID> ids = new HashMap<>();
        Map<String, String> types = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, code, type FROM account WHERE tenant_id = ? AND code IN (?, ?, ?, ?)")) {
            ps.setObject(1, tenantId);
            for (int i = 0; i < SPECS.length; i++) {
                ps.setString(2 + i, SPECS[i].code());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.put(rs.getString("code"), rs.getObject("id", UUID.class));
                    types.put(rs.getString("code"), rs.getString("type"));
                }
            }
        }
        for (Spec spec : SPECS) {
            if (!ids.containsKey(spec.code()) || !spec.type().name().equals(types.get(spec.code()))) {
                throw new IllegalStateException("The chart of accounts is not usable for this tenant");   // no ids, no codes in the message
            }
        }
        return new Accounts(ids.get(CLEARING_CODE), ids.get(RECEIVABLE_CODE), ids.get(CUSTOMER_CREDIT_CODE), ids.get(REVENUE_CODE));
    }
}
