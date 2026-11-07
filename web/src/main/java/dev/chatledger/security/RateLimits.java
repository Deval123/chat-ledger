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
}
