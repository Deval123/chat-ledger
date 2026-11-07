package dev.chatledger.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates workspace + email + password.
 *
 * <p>Every failure is the same exception, whatever the cause: malformed input, unknown workspace,
 * unknown email, wrong password, disabled user, disabled workspace. And every path does the same
 * work: one workspace lookup, one user lookup, one Argon2 check. On an unknown workspace the user
 * lookup runs against a random tenant id and the password is checked against a dummy hash, so
 * neither the response nor its timing says which part was wrong.
 *
 * <p>The tenant, user id and role of the result come from the database row, never from the form.
 */
@Component
public class LoginAuthenticationProvider implements AuthenticationProvider {

    private static final int MAX_PASSWORD_LENGTH = PasswordPolicy.MAX_LENGTH;

    private final TenantResolver tenants;
    private final AppUserRepository users;
    private final PasswordVerifier passwords;

    public LoginAuthenticationProvider(TenantResolver tenants, AppUserRepository users, PasswordVerifier passwords) {
        this.tenants = tenants;
        this.users = users;
        this.passwords = passwords;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        var attempt = (WorkspaceAuthenticationToken) authentication;

        Optional<String> workspace = Identifiers.workspace(attempt.workspace());
        Optional<String> email = Identifiers.email(attempt.email());
        String password = attempt.password();
        boolean wellFormed = workspace.isPresent() && email.isPresent()
                && password != null && !password.isEmpty() && password.length() <= MAX_PASSWORD_LENGTH;

        // Same three steps on every path. Malformed input uses values that cannot match anything.
        Optional<UUID> tenant = tenants.resolve(wellFormed ? workspace.get() : Identifiers.IMPOSSIBLE_WORKSPACE);
        UUID lookupTenant = tenant.orElseGet(UUID::randomUUID);
        Optional<AppUser> user = users.findByEmail(lookupTenant, wellFormed ? email.get() : "unknown@example.invalid");

        String hash = user.filter(u -> wellFormed).map(AppUser::passwordHash).orElse(passwords.dummyHash());
        boolean passwordMatches = passwords.matches(wellFormed ? password : "x", hash);

        if (!wellFormed || tenant.isEmpty() || user.isEmpty() || !user.get().enabled() || !passwordMatches) {
            throw new BadCredentialsException("Bad credentials");
        }
        AppUser found = user.get();
        attempt.eraseCredentials();
        return WorkspaceAuthenticationToken.signedIn(new TenantUserDetails(found.id(), found.tenantId(), found.role()));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return WorkspaceAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
