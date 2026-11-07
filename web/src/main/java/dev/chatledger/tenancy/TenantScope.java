package dev.chatledger.tenancy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * The only way tenant data is reached with plain JDBC: one database transaction
 * in which the tenant is set first.
 *
 * <p>The tenant is set with {@code set_config('app.tenant_id', ..., true)}. The
 * {@code true} makes it local to the transaction, so it disappears at commit or
 * rollback and a pooled connection never carries a tenant to its next borrower.
 * PostgreSQL row-level security policies compare against it. Code that skips this
 * class sees nothing and can write nothing, because the policies fail closed.
 *
 * <p>The tenant id passed in must come from the authenticated principal (or from
 * a row the database returned for it), never from request input.
 *
 * <p>The deferred constraint triggers fire at {@code commit()}, inside this
 * class, so a violation surfaces as a {@link SQLException} from {@link #call}.
 *
 * <p>Design: "Tenancy and authentication", decision 2. Task 3.2.
 */
public final class TenantScope {

    /** Work to run on the tenant-scoped connection. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final DataSource dataSource;

    public TenantScope(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Runs the work in one transaction for the tenant, then commits. */
    public <T> T call(UUID tenantId, SqlWork<T> work) throws SQLException {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(work, "work");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                setTenant(connection, tenantId);
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection, failure);
                throw failure;
            }
        }
    }

    private static void setTenant(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenantId.toString());
            ps.execute();
        }
    }

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
