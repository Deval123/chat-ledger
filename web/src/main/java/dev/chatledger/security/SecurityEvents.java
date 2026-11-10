package dev.chatledger.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The security log. Each method takes only identifiers and a keyed hash. There is no parameter
 * that could carry a password, a hash, a session id, a CSRF token or an email, so none can be
 * logged by mistake.
 *
 * <p>Failures include the client address (needed to follow up an attack). That is personal data and
 * is named as such in the design's trade-off register.
 */
@Component
public class SecurityEvents {

    private static final Logger LOG = LoggerFactory.getLogger("security.events");

    public void loginSucceeded(UUID userId, UUID tenantId) {
        LOG.info("login succeeded user={} tenant={}", userId, tenantId);
    }

    /** @param attemptKey keyed hash of the submitted workspace and email, not the values themselves */
    public void loginFailed(String attemptKey, String clientAddress) {
        LOG.warn("login failed attempt={} client={}", attemptKey, clientAddress);
    }

    public void loginRefusedBusy(String clientAddress) {
        LOG.warn("login refused, too many password checks at once client={}", clientAddress);
    }

    /** A visitor was given a demo workspace (task 9). */
    public void demoEntered(UUID userId, UUID tenantId) {
        LOG.info("demo entered user={} tenant={}", userId, tenantId);
    }

    /** @param reason a fixed label: no workspace was ready, or the database returned one that is not a usable demo seller */
    public void demoRefused(String reason) {
        LOG.warn("demo refused reason={}", reason);
    }

    public void loggedOut(UUID userId, UUID tenantId) {
        LOG.info("logout user={} tenant={}", userId, tenantId);
    }

    public void sessionEnded(String reason, UUID userId, UUID tenantId) {
        LOG.info("session ended reason={} user={} tenant={}", reason, userId, tenantId);
    }

    public void accessDenied(UUID userIdOrNull, String path) {
        LOG.warn("access denied user={} path={}", userIdOrNull, path);
    }

    public void csrfRejected(String path) {
        LOG.warn("request rejected: missing or invalid CSRF token path={}", path);
    }

    public void auditWriteFailed(String action) {
        LOG.error("could not write the audit trail for action={}", action);
    }

    /** @param which a fixed label such as "login-address" or "login-account" */
    public void rateLimited(String which, String clientAddress) {
        LOG.warn("rate limit hit limit={} client={}", which, clientAddress);
    }

    /** @param reason a fixed label (never request text, so the log cannot be injected into) */
    public void requestRejected(String reason) {
        LOG.warn("request rejected reason={}", reason);
    }
}
