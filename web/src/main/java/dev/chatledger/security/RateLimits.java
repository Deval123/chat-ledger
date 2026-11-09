package dev.chatledger.security;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The named limits of this application over one {@link RateLimiter}, so callers say what they are
 * limiting and not how.
 */
@Component
public class RateLimits {

    static final String LOGIN_IP = "login-ip";
    static final String LOGIN_ACCOUNT = "login-account";
    static final String LOGIN_PAGE_IP = "login-page-ip";
    public static final String INGESTION_TENANT = "ingestion-tenant";
    static final String REMATCH_MINUTE = "rematch-minute";
    static final String REMATCH_HOUR = "rematch-hour";
    static final String EXPORT_TENANT = "export-tenant";
    static final String ACTIONS_USER = "actions-user";

    private final RateLimiter limiter;

    public RateLimits(RateLimiter limiter) {
        this.limiter = limiter;
    }

    /** Each sign-in attempt from one address. */
    public RateDecision loginFromAddress(String address) {
        return limiter.tryAcquire(LOGIN_IP, address);
    }

    /** Each load of the sign-in page (each creates an anonymous session). */
    public RateDecision loginPageFromAddress(String address) {
        return limiter.tryAcquire(LOGIN_PAGE_IP, address);
    }

    /**
     * Each sign-in attempt for one submitted account, whether or not it exists. The key is the keyed
     * hash of workspace and email, so the answer is the same for real and invented accounts.
     */
    public RateDecision loginForAccount(String accountKey) {
        return limiter.tryAcquire(LOGIN_ACCOUNT, accountKey);
    }

    public void loginSucceeded(String accountKey) {
        limiter.reset(LOGIN_ACCOUNT, accountKey);
    }

    /** Message ingestion for one tenant. The tenant must come from the principal, never the request. */
    public RateDecision ingestion(UUID tenantId) {
        return limiter.tryAcquire(INGESTION_TENANT, tenantId.toString());
    }

    /**
     * A re-match of every open order for one tenant. Both the per-minute and the per-hour limit must
     * allow it; the first refusal is returned. A refused call spends nothing from the other limit.
     */
    public RateDecision rematch(UUID tenantId) {
        RateDecision minute = limiter.tryAcquire(REMATCH_MINUTE, tenantId.toString());
        if (!minute.allowed()) {
            return minute;
        }
        return limiter.tryAcquire(REMATCH_HOUR, tenantId.toString());
    }

    /** One CSV export for one tenant. */
    public RateDecision export(UUID tenantId) {
        return limiter.tryAcquire(EXPORT_TENANT, tenantId.toString());
    }

    /** Each state-changing action of one signed-in user (confirm, reject, approve, paste, ...). */
    public RateDecision action(UUID userId) {
        return limiter.tryAcquire(ACTIONS_USER, userId.toString());
    }
}
