package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    @Autowired private SecurityUtil securityUtil;

    /**
     * Returns the authenticated user's ID from the SecurityContext.
     * The JwtAuthFilter populates the context from the HttpOnly accessToken cookie.
     */
    public String getUserId() {
        String userId = securityUtil.getUsername();
        log.debug("Resolved userId from SecurityContext: {}", userId);
        return userId;
    }

    /**
     * Returns the authenticated user's role (without the ROLE_ prefix) from the SecurityContext.
     */
    public String getRole() {
        String role = securityUtil.getRole();
        log.debug("Resolved role from SecurityContext: {}", role);
        return role;
    }
}