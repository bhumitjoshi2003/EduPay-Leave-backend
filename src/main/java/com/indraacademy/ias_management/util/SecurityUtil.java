package com.indraacademy.ias_management.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class SecurityUtil {

    public String getUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return "SYSTEM";
        }

        return authentication.getName();
    }

    public String getRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || authentication.getAuthorities() == null) {
            return "UNKNOWN";
        }

        String authority = authentication.getAuthorities()
                .stream()
                .findFirst()
                .map(GrantedAuthority::getAuthority)
                .orElse("UNKNOWN");

        return authority.startsWith("ROLE_") ? authority.substring(5) : authority;
    }
}