package dev.chatledger.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * A login attempt (before authentication) or the signed-in user (after). The attempt carries the
 * three things typed on the form; {@code toString} prints none of them.
 */
public final class WorkspaceAuthenticationToken extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final String workspace;
    private final String email;
    private String password;
    private final TenantUserDetails principal;

    private WorkspaceAuthenticationToken(String workspace, String email, String password) {
        super(AuthorityUtils.NO_AUTHORITIES);
        this.workspace = workspace;
        this.email = email;
        this.password = password;
        this.principal = null;
        setAuthenticated(false);
    }

    private WorkspaceAuthenticationToken(TenantUserDetails principal) {
        super(principal.getAuthorities());
        this.workspace = null;
        this.email = null;
        this.password = null;
        this.principal = principal;
        setAuthenticated(true);
    }

    public static WorkspaceAuthenticationToken attempt(String workspace, String email, String password) {
        return new WorkspaceAuthenticationToken(workspace, email, password);
    }

    public static WorkspaceAuthenticationToken signedIn(TenantUserDetails principal) {
        return new WorkspaceAuthenticationToken(principal);
    }

    public String workspace() {
        return workspace;
    }

    public String email() {
        return email;
    }

    public String password() {
        return password;
    }

    @Override
    public Object getCredentials() {
        return password;
    }

    @Override
    public Object getPrincipal() {
        return principal != null ? principal : "[login attempt]";
    }

    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
        this.password = null;
    }

    @Override
    public String toString() {
        return principal != null ? "WorkspaceAuthenticationToken[" + principal + "]" : "WorkspaceAuthenticationToken[attempt]";
    }
}
