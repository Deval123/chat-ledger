package dev.chatledger.security;

import java.time.Instant;
import java.util.UUID;

/**
 * A user row as read for authentication. {@code toString} leaves out the hash and the email, so
 * logging an instance by accident cannot leak either.
 */
public record AppUser(
        UUID id,
        UUID tenantId,
        String email,
        String passwordHash,
        Role role,
        Instant disabledAt,
        Instant tenantDisabledAt) {

    /** True when neither the user nor their workspace has been disabled. */
    public boolean enabled() {
        return disabledAt == null && tenantDisabledAt == null;
    }

    @Override
    public String toString() {
        return "AppUser[id=" + id + ", tenant=" + tenantId + ", role=" + role + ", enabled=" + enabled() + "]";
    }
}
