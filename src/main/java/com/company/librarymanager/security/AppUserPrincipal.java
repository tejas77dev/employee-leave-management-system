package com.company.librarymanager.security;

import com.company.librarymanager.domain.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * The signed-in identity, held in the session as plain values.
 *
 * <p>Deliberately not the {@code User} entity. An entity would drag lazy
 * proxies into the HTTP session, which then fail to deserialise on the next
 * request once the persistence context has closed.
 */
public final class AppUserPrincipal implements UserDetails, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String email;
    private final String name;
    private final Role role;
    private final boolean active;
    private final String passwordHash;

    public AppUserPrincipal(String id, String email, String name, Role role, boolean active,
                            String passwordHash) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.role = role;
        this.active = active;
        this.passwordHash = passwordHash;
    }

    public String id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String name() {
        return name;
    }

    public Role role() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        // Only the freshly-loaded authentication principal carries a hash, and
        // only long enough to be checked. The copy cached in the session has
        // none, so a hash is never serialised into the session store.
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    /** Up to two initials, for the avatar chip. */
    public String initials() {
        String[] parts = name.trim().split("\\s+");
        StringBuilder initials = new StringBuilder();
        for (int i = 0; i < Math.min(2, parts.length); i++) {
            if (!parts[i].isEmpty()) {
                initials.append(Character.toUpperCase(parts[i].charAt(0)));
            }
        }
        return initials.toString();
    }

    /** The same identity without a password hash, for storing in a session. */
    public AppUserPrincipal withoutPassword() {
        return new AppUserPrincipal(id, email, name, role, active, null);
    }

    public String firstName() {
        String trimmed = name.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }
}
