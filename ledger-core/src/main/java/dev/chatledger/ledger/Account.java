package dev.chatledger.ledger;

import java.util.UUID;

/**
 * A ledger account belonging to a single tenant.
 *
 * <p>Amounts are always integers in minor currency units (XAF has scale 0).
 * Floating-point is never used for money.
 *
 * @param id       Unique account identifier.
 * @param tenantId Owning tenant — every query must be scoped by this value.
 * @param code     Short mnemonic (e.g. "AR", "MMCLR").
 * @param name     Human-readable label shown in the UI.
 * @param type     One of the five standard account types.
 */
public record Account(
        UUID id,
        UUID tenantId,
        String code,
        String name,
        AccountType type
) {
    public Account {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Account code must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Account name must not be blank");
    }
}
