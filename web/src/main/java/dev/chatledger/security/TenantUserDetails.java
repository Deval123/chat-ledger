package dev.chatledger.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The signed-in user. This is the only source of "which tenant am I acting for": the tenant id
 * here comes from the database row found at login, never from a form field or a request
 * parameter.
 *
 * <p>It holds no password or hash and no email: the principal is stored in the session and
 * can end up in logs, so it carries only identifiers and the role.
 */
public final class TenantUserDetails implements UserDetails {

    private static final long serialVersionUID = 1L;

    private final UUID userId;
    private final UUID tenantId;
    private final Role role;
    private final List<SimpleGrantedAuthority> authorities;

    public TenantUserDetails(UUID userId, UUID tenantId, Role role) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.role = Objects.requireNonNull(role, "role");
        List<SimpleGrantedAuthority> list = new ArrayList<>();
        list.add(new SimpleGrantedAuthority(role.authority()));
        role.permissions().stream().sorted().forEach(p -> list.add(new SimpleGrantedAuthority(p.name())));
        this.authorities = List.copyOf(list);
    }

    public UUID userId() {
        return userId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public Role role() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return null;
    }

    /** The user id, not the email, so the principal's name is safe to log. */
    @Override
    public String getUsername() {
        return userId.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TenantUserDetails that && userId.equals(that.userId)
                && tenantId.equals(that.tenantId) && role == that.role;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, tenantId, role);
    }

    @Override
    public String toString() {
        return "TenantUserDetails[user=" + userId + ", tenant=" + tenantId + ", role=" + role + "]";
    }
}
