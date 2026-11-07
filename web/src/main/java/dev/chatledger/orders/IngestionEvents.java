package dev.chatledger.orders;

import dev.chatledger.extraction.FailureReason;
import dev.chatledger.ingestion.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The ingestion log. Every method takes identifiers, enums and counts only. There is no parameter that could carry a
 * name, a phone number, an item label or any of the message, so none can be logged by mistake, and a message cannot
 * forge a log line.
 */
@Component
public class IngestionEvents {

    private static final Logger LOG = LoggerFactory.getLogger("ingestion.events");

    public void created(UUID tenantId, UUID orderId, IngestionResult.Outcome outcome, FailureReason reason, int lines) {
        LOG.info("order ingested tenant={} order={} outcome={} reason={} lines={}", tenantId, orderId, outcome, reason, lines);
    }

    public void duplicate(UUID tenantId, UUID orderId, boolean differentText) {
        if (differentText) {
            LOG.warn("order replay with different text, first order kept tenant={} order={}", tenantId, orderId);
        } else {
            LOG.info("order replay tenant={} order={}", tenantId, orderId);
        }
    }

    public void rejected(UUID tenantId, RejectReason reason) {
        LOG.warn("order input rejected tenant={} reason={}", tenantId, reason);
    }

    public void rateLimited(UUID tenantId) {
        LOG.warn("order ingestion rate limit hit tenant={}", tenantId);
    }
}
