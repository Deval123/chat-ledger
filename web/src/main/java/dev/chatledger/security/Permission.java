package dev.chatledger.security;

/**
 * Everything a signed-in user can be allowed to do. Code asks for a permission, never for a
 * role, so who may do what lives in one table ({@link Role}).
 *
 * <p>Design: "Tenancy and authentication", decision 4.
 */
public enum Permission {
    VIEW_BOARD,
    RECORD_ORDER,
    RECORD_PAYMENT,
    /** Confirm, reject, manually match or unmatch: the seller has the last word. */
    CONFIRM_OR_OVERRIDE_MATCH,
    RESOLVE_FLAG,
    VIEW_LEDGER,
    REVERSE_TRANSACTION,
    VIEW_AUDIT_LOG,
    EXPORT_DATA,
    DELETE_TENANT_DATA,
    MANAGE_USERS
}
