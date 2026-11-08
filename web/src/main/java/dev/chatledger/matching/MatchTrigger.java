package dev.chatledger.matching;

import java.util.UUID;

/**
 * Told by the ingestion services, after their own transaction has committed, that an order or a payment was stored. The matcher is the
 * implementation. Whatever it does, it must not change what the ingestion returned: a failure here is logged and the record stays stored.
 */
public interface MatchTrigger {

    /** Does nothing. For tests that exercise ingestion alone. */
    MatchTrigger NONE = new MatchTrigger() {
        @Override
        public void paymentStored(UUID tenantId, UUID paymentId) {
        }

        @Override
        public void orderStored(UUID tenantId, UUID orderId) {
        }
    };

    /** A payment was stored with status RECEIVED or UNVERIFIED. */
    void paymentStored(UUID tenantId, UUID paymentId);

    /** An order was stored with status PENDING_PAYMENT. */
    void orderStored(UUID tenantId, UUID orderId);
}
