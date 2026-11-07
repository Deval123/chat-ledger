package dev.chatledger.ledger;

import java.util.Objects;
import java.util.UUID;

/**
 * A ledger account belonging to exactly one tenant.
 *
 * @param id       Unique account identifier.
 * @param tenantId Owning tenant. Every query is scoped by this value.
 * @param code     Short mnemonic, unique per tenant (for example "AR").
 * @param name     Human-readable label.
 * @param type     One of the five standard account types.
 */
public record Account(UUID id, UUID tenantId, String code, String name, AccountType type) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(type, "type");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Account code must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Account name must not be blank");
        }
    }
}
