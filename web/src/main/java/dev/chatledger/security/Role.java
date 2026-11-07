package dev.chatledger.security;

import java.util.EnumSet;
import java.util.Set;

/**
 * The two roles and exactly what each may do.
 *
 * <p>A HELPER records sales and sees the board. A HELPER never exports or deletes tenant data,
 * manages users, decides matches, resolves flags, or touches the books: the seller has the last
 * word. Anything not listed here is not allowed.
 */
public enum Role {
    SELLER(EnumSet.allOf(Permission.class)),
    HELPER(EnumSet.of(Permission.VIEW_BOARD, Permission.RECORD_ORDER, Permission.RECORD_PAYMENT));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean allows(Permission permission) {
        return permissions.contains(permission);
    }

    /** The authority string for this role, used for coarse URL rules such as /admin/**. */
    public String authority() {
        return "ROLE_" + name();
    }
}
