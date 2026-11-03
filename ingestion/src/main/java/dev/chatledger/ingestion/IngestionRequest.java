package dev.chatledger.ingestion;

/**
 * Payload for the machine ingestion endpoint.
 *
 * <p>The {@code externalId} provides idempotency: submitting the same
 * external id twice for the same tenant produces exactly one record.
 *
 * @param externalId Caller-assigned unique identifier for this message.
 * @param text       Raw message text (order chat or confirmation SMS).
 */
public record IngestionRequest(String externalId, String text) {

    public IngestionRequest {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
