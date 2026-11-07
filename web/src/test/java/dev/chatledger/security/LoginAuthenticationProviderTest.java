package dev.chatledger.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every outcome of a sign-in attempt does the same work: one workspace lookup, one user lookup, one
 * password check. The response cannot tell the cases apart, and neither can the time, because none
 * of them skips the expensive step.
 *
 * <p>Requirement: 9.1. Task 3.4.
 */
class LoginAuthenticationProviderTest {

    private static final String HASH = "{argon2id}$argon2id$v=19$m=19456,t=2,p=1$AAAA$BBBB";
    private static final String DUMMY = "{argon2id}$argon2id$v=19$m=19456,t=2,p=1$DUMMY$DUMMY";

    private TenantResolver tenants;
    private AppUserRepository users;
    private PasswordVerifier passwords;
    private LoginAuthenticationProvider provider;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tenants = mock(TenantResolver.class);
        users = mock(AppUserRepository.class);
        passwords = mock(PasswordVerifier.class);
        when(passwords.dummyHash()).thenReturn(DUMMY);
        provider = new LoginAuthenticationProvider(tenants, users, passwords);
    }

    private AppUser user(Role role, Instant disabled, Instant tenantDisabled) {
        return new AppUser(UUID.randomUUID(), tenantId, "ana@example.test", HASH, role, disabled, tenantDisabled);
    }

    private void attempt(String workspace, String email, String password) {
        provider.authenticate(WorkspaceAuthenticationToken.attempt(workspace, email, password));
    }

    private void assertSameWork() {
        verify(tenants, times(1)).resolve(anyString());
        verify(users, times(1)).findByEmail(any(), anyString());
        verify(passwords, times(1)).matches(anyString(), anyString());
    }

    @Test
    void success_returns_the_tenant_user_and_role_from_the_database() {
        AppUser found = user(Role.HELPER, null, null);
        when(tenants.resolve("river-shop")).thenReturn(Optional.of(tenantId));
        when(users.findByEmail(tenantId, "ana@example.test")).thenReturn(Optional.of(found));
        when(passwords.matches("a-long-password-1", HASH)).thenReturn(true);

        Authentication result = provider.authenticate(
                WorkspaceAuthenticationToken.attempt(" River-Shop ", " ANA@example.test ", "a-long-password-1"));

        assertThat(result.isAuthenticated()).isTrue();
        var principal = (TenantUserDetails) result.getPrincipal();
        assertThat(principal.tenantId()).isEqualTo(tenantId);
        assertThat(principal.userId()).isEqualTo(found.id());
        assertThat(principal.role()).isEqualTo(Role.HELPER);
        assertThat(result.getCredentials()).isNull();
        assertSameWork();
    }

    @Test
    void a_wrong_password_fails_after_the_same_work() {
        when(tenants.resolve("river-shop")).thenReturn(Optional.of(tenantId));
        when(users.findByEmail(tenantId, "ana@example.test")).thenReturn(Optional.of(user(Role.SELLER, null, null)));
        when(passwords.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> attempt("river-shop", "ana@example.test", "wrong-password-1"))
                .isInstanceOf(BadCredentialsException.class);
        assertSameWork();
    }

    @Test
    void an_unknown_workspace_still_looks_up_a_user_and_checks_a_password_against_the_dummy_hash() {
        when(tenants.resolve("nowhere")).thenReturn(Optional.empty());
        when(users.findByEmail(any(), anyString())).thenReturn(Optional.empty());
        when(passwords.matches(anyString(), anyString())).thenReturn(true);   // even a "match" must not sign in

        assertThatThrownBy(() -> attempt("nowhere", "ana@example.test", "a-long-password-1"))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> attempt("nowhere", "ana@example.test", "a-long-password-1"))
                .isInstanceOf(BadCredentialsException.class);

        ArgumentCaptor<UUID> tenant = ArgumentCaptor.forClass(UUID.class);
        verify(users, times(2)).findByEmail(tenant.capture(), anyString());
        assertThat(tenant.getAllValues()).as("a random tenant id each time, never a real one").doesNotContain(tenantId)
                .doesNotHaveDuplicates();
        verify(passwords, times(2)).matches("a-long-password-1", DUMMY);
    }

    @Test
    void an_unknown_email_checks_the_password_against_the_dummy_hash() {
        when(tenants.resolve("river-shop")).thenReturn(Optional.of(tenantId));
        when(users.findByEmail(tenantId, "nobody@example.test")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> attempt("river-shop", "nobody@example.test", "a-long-password-1"))
                .isInstanceOf(BadCredentialsException.class);
        assertSameWork();
        verify(passwords).matches("a-long-password-1", DUMMY);
    }

    @Test
    void a_disabled_user_or_workspace_fails_even_with_the_right_password() {
        when(tenants.resolve("river-shop")).thenReturn(Optional.of(tenantId));
        when(passwords.matches(anyString(), anyString())).thenReturn(true);

        when(users.findByEmail(tenantId, "ana@example.test")).thenReturn(Optional.of(user(Role.SELLER, Instant.now(), null)));
        assertThatThrownBy(() -> attempt("river-shop", "ana@example.test", "a-long-password-1"))
                .isInstanceOf(BadCredentialsException.class);

        when(users.findByEmail(tenantId, "ana@example.test")).thenReturn(Optional.of(user(Role.SELLER, null, Instant.now())));
        assertThatThrownBy(() -> attempt("river-shop", "ana@example.test", "a-long-password-1"))
                .isInstanceOf(BadCredentialsException.class);
        // The password check ran both times, with the real hash.
        verify(passwords, times(2)).matches("a-long-password-1", HASH);
    }

    @Test
    void malformed_input_does_the_same_work_with_values_that_match_nothing() {
        when(tenants.resolve(Identifiers.IMPOSSIBLE_WORKSPACE)).thenReturn(Optional.empty());
        when(users.findByEmail(any(), anyString())).thenReturn(Optional.empty());
        when(passwords.matches(anyString(), anyString())).thenReturn(true);

        for (String[] bad : new String[][]{{"bad workspace!", "ana@example.test", "a-long-password-1"},
                {"river-shop", "not-an-email", "a-long-password-1"}, {"river-shop", "ana@example.test", ""},
                {"river-shop", "ana@example.test", "x".repeat(PasswordPolicy.MAX_LENGTH + 1)}, {null, null, null}}) {
            assertThatThrownBy(() -> attempt(bad[0], bad[1], bad[2])).isInstanceOf(BadCredentialsException.class);
        }
        verify(tenants, times(5)).resolve(Identifiers.IMPOSSIBLE_WORKSPACE);
        verify(users, times(5)).findByEmail(any(), anyString());
        verify(passwords, times(5)).matches("x", DUMMY);
    }

    @Test
    void a_password_of_the_maximum_length_is_still_checked_against_the_real_hash() {
        String longest = "p".repeat(PasswordPolicy.MAX_LENGTH);
        when(tenants.resolve("river-shop")).thenReturn(Optional.of(tenantId));
        when(users.findByEmail(tenantId, "ana@example.test")).thenReturn(Optional.of(user(Role.SELLER, null, null)));
        when(passwords.matches(longest, HASH)).thenReturn(true);
        assertThat(provider.authenticate(WorkspaceAuthenticationToken.attempt("river-shop", "ana@example.test", longest))
                .isAuthenticated()).isTrue();
    }

    @Test
    void the_attempt_never_prints_what_was_typed() {
        assertThat(WorkspaceAuthenticationToken.attempt("river-shop", "ana@example.test", "secret-typed-1").toString())
                .doesNotContain("river-shop", "ana@example.test", "secret-typed-1");
    }
}
