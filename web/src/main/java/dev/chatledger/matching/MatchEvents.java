package dev.chatledger.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The matching log. Every method takes identifiers, enums and numbers. There is no parameter that could carry a name, a reference, a
 * phone number or any part of a message, so none can be logged by mistake and a message cannot forge a log line.
 */
@Component
public class MatchEvents {

    private static final Logger LOG = LoggerFactory.getLogger("match.events");

    public void applied(UUID tenantId, UUID matchId, MatchingService.Mode mode, long appliedMinor, long excessMinor, String orderStatus) {
        LOG.info("match applied tenant={} match={} mode={} applied={} excess={} order={}", tenantId, matchId, mode, appliedMinor, excessMinor, orderStatus);
    }

    public void notApplicable(UUID tenantId, UUID orderId, UUID paymentId, MatchingService.Reason reason) {
        LOG.info("match not applicable tenant={} order={} payment={} reason={}", tenantId, orderId, paymentId, reason);
    }

    public void degraded(UUID tenantId, UUID orderId, UUID paymentId) {
        LOG.info("automatic match became a suggestion tenant={} order={} payment={}", tenantId, orderId, paymentId);
    }

    public void suggested(UUID tenantId, int count) {
        LOG.info("match suggestions stored tenant={} count={}", tenantId, count);
    }

    public void failed(UUID tenantId, String subject, UUID id) {
        LOG.warn("matching failed, the record stays unmatched tenant={} {}={}", tenantId, subject, id);
    }
}
