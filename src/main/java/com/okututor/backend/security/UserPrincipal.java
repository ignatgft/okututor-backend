package com.okututor.backend.security;

import com.okututor.backend.user.Role;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record UserPrincipal(java.util.UUID id, String email, Role role) implements UserDetails {

    @Override
    public List<GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return email;
    }

    public boolean isAdminLike() {
        return role == Role.ADMIN || role == Role.SUPER_ADMIN;
    }
}
