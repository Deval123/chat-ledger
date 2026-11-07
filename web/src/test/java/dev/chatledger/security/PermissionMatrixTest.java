package dev.chatledger.security;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static dev.chatledger.security.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role/permission table, checked against a second table written out here. Changing who
 * may do what must change both, on purpose. Anything not listed for HELPER is denied.
 *
 * <p>Requirement: 9.1 (roles), design "Tenancy and authentication". Task 3.7.
 */
class PermissionMatrixTest {

    private static final Set<Permission> HELPER_MAY = EnumSet.of(VIEW_BOARD, RECORD_ORDER, RECORD_PAYMENT);

    @Test
    void every_permission_for_every_role_matches_the_agreed_table() {
        for (Role role : Role.values()) {
            for (Permission permission : Permission.values()) {
                boolean expected = role == Role.SELLER || HELPER_MAY.contains(permission);
                assertThat(role.allows(permission)).as(role + " " + permission).isEqualTo(expected);
            }
        }
    }

    @Test
    void a_helper_never_exports_deletes_manages_users_decides_matches_or_touches_the_books() {
        for (Permission forbidden : new Permission[]{EXPORT_DATA, DELETE_TENANT_DATA, MANAGE_USERS,
                CONFIRM_OR_OVERRIDE_MATCH, RESOLVE_FLAG, VIEW_LEDGER, REVERSE_TRANSACTION, VIEW_AUDIT_LOG}) {
            assertThat(Role.HELPER.allows(forbidden)).as(forbidden.name()).isFalse();
        }
    }

    @Test
    void the_set_of_permissions_cannot_be_changed_from_outside() {
        Set<Permission> set = Role.HELPER.permissions();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> set.add(EXPORT_DATA))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void the_authority_name_is_derived_from_the_role() {
        assertThat(Role.SELLER.authority()).isEqualTo("ROLE_SELLER");
        assertThat(Role.HELPER.authority()).isEqualTo("ROLE_HELPER");
    }
}
