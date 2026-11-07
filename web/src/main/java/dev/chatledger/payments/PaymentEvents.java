package dev.chatledger.payments;

import dev.chatledger.extraction.FailureReason;
import dev.chatledger.ingestion.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The payment ingestion log. Every method takes identifiers, enums, counts and the id of a shipped template. There is no
 * parameter that could carry a name, a phone number, a transaction id, a reference or any of the message, so none can be logged
 * by mistake, and a message cannot forge a log line.
 */
@Component
public class PaymentEvents {

    private static final Logger LOG = LoggerFactory.getLogger("payment.events");

    public void created(UUID tenantId, UUID paymentId, PaymentIngestionResult.Outcome outcome, FailureReason reason, String templateId) {
        LOG.info("payment ingested tenant={} payment={} outcome={} reason={} template={}", tenantId, paymentId, outcome, reason, templateId);
    }

    public void replay(UUID tenantId, UUID paymentId, boolean differentText) {
        if (differentText) {
            LOG.warn("payment replay with different text, first payment kept tenant={} payment={}", tenantId, paymentId);
        } else {
            LOG.info("payment replay tenant={} payment={}", tenantId, paymentId);
        }
    }

    public void rejected(UUID tenantId, RejectReason reason) {
        LOG.warn("payment input rejected tenant={} reason={}", tenantId, reason);
    }

    public void rateLimited(UUID tenantId) {
        LOG.warn("payment ingestion rate limit hit tenant={}", tenantId);
    }
}
