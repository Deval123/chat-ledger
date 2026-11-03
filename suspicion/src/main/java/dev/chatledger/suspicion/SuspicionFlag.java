package dev.chatledger.suspicion;

import java.time.Instant;
import java.util.UUID;

/**
 * An advisory flag raised by a suspicion rule.
 *
 * <p>Flags are advisory only — the seller always has the last word (Requirement 5).
 * Every flag carries a plain-language reason so the seller understands the concern.
 *
 * @param id          Unique flag identifier.
 * @param tenantId    Owning tenant.
 * @param subjectType Type of the flagged entity (e.g. "PAYMENT", "MATCH").
 * @param subjectId   Identifier of the flagged entity.
 * @param code        Machine-readable rule code (e.g. {@code DUPLICATE_TX}).
 * @param reason      Plain English sentence explaining why the flag was raised.
 * @param createdAt   When the rule raised this flag.
 * @param resolvedAt  When the seller resolved the flag, or {@code null} if open.
 */
public record SuspicionFlag(
        UUID id,
        UUID tenantId,
        String subjectType,
        UUID subjectId,
        FlagCode code,
        String reason,
        Instant createdAt,
        Instant resolvedAt
) {
    public SuspicionFlag {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Flag reason must not be blank");
        }
    }

    public boolean isOpen() { return resolvedAt == null; }
}
