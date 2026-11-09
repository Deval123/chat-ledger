package dev.chatledger.flags;

import dev.chatledger.matching.MatchRepository;
import dev.chatledger.security.AuditLogRepository;
import dev.chatledger.tenancy.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The seller resolves a flag: "I checked this". One transaction: the database is asked whether the user is an enabled seller of the tenant,
 * the flag is locked, it is resolved (who and when) only if it is still open, and an audit row {@code FLAG_RESOLVED} is written. The
 * database refuses any other change and any second resolution (V019). Resolving changes no money and does not hand the pair back to the
 * engine: a pair with any flag, open or resolved, stays suggestion-only (task 7, D3).
 */
@Service
public class FlagService {
    private static final Logger LOG = LoggerFactory.getLogger("flag.events");

    public enum Outcome { RESOLVED, ALREADY_RESOLVED, NOT_FOUND, NOT_A_SELLER }

    /** What happened, and the page of the flag ({@code /app/payments/..} or {@code /app/orders/..}), built from ids the database returned. */
    public record Result(Outcome outcome, String page) {
    }

    private final TenantScope scope;
    private final MatchRepository matches;
    private final FlagRepository flags;
    private final Clock clock;

    public FlagService(DataSource dataSource, MatchRepository matches, FlagRepository flags, Clock clock) {
        this.scope = new TenantScope(dataSource);
        this.matches = matches;
        this.flags = flags;
        this.clock = clock;
    }

    public Result resolve(UUID tenantId, UUID flagId, UUID actor) {
        Objects.requireNonNull(flagId, "flagId");
        Objects.requireNonNull(actor, "actor");
        Result result;
        try {
            result = scope.call(tenantId, c -> {
                if (!matches.isSellerWithin(c, tenantId, actor)) {
                    return new Result(Outcome.NOT_A_SELLER, null);
                }
                Optional<FlagRepository.Stored> flag = flags.lockWithin(c, tenantId, flagId);
                if (flag.isEmpty()) {
                    return new Result(Outcome.NOT_FOUND, null);
                }
                FlagRepository.Stored f = flag.get();
                String page = "PAYMENT".equals(f.subjectType()) ? "/app/payments/" + f.paymentId() : "/app/orders/" + f.orderId();
                // Only an open flag is resolved (the statement says so, and the row is locked): 0 rows means it was already resolved.
                if (flags.resolveWithin(c, tenantId, flagId, actor, clock.instant()) != 1) {
                    return new Result(Outcome.ALREADY_RESOLVED, page);
                }
                AuditLogRepository.appendWithin(c, tenantId, actor, "FLAG_RESOLVED", flagId.toString());
                return new Result(Outcome.RESOLVED, page);
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not resolve a flag", e);
        }
        LOG.info("flag resolve tenant={} flag={} outcome={}", tenantId, flagId, result.outcome());
        return result;
    }
}
