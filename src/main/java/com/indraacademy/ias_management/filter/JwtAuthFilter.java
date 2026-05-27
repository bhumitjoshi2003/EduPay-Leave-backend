package com.indraacademy.ias_management.filter;

import com.indraacademy.ias_management.util.JwtUtil;
import com.indraacademy.ias_management.util.SchoolContext;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.indraacademy.ias_management.repository.RolePermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Bypass auth for public endpoints and static uploads (permitAll in SecurityConfig)
        if (path.startsWith("/api/auth/login")
                || path.startsWith("/api/auth/logout")
                || path.startsWith("/api/auth/refresh-token")
                || path.startsWith("/api/auth/request-password-reset")
                || path.startsWith("/api/auth/reset-password")
                || path.startsWith("/api/public/")
                || path.startsWith("/api/uploads/")
                || path.startsWith("/api/files/")) {

            filterChain.doFilter(request, response);
            return;
        }

        // When multiple "accessToken" cookies exist (e.g. stale cookies from a previous
        // session at a different school subdomain alongside a freshly issued cookie),
        // WebUtils.getCookie returns only the first match — which may be the wrong one.
        // Instead: collect all accessToken cookies, skip expired/invalid ones, and use
        // the most recently issued valid token (highest iat claim).
        Cookie[] allCookies = request.getCookies();
        if (allCookies == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String token    = null;
        String userId   = null;
        String role     = null;
        Long   schoolId = null;
        long   bestIat  = Long.MIN_VALUE;

        for (Cookie c : allCookies) {
            if (!"accessToken".equals(c.getName())) continue;
            try {
                String candidateToken    = c.getValue();
                long   candidateIat      = jwtUtil.extractIssuedAt(candidateToken).getTime();
                String candidateUserId   = jwtUtil.extractUserId(candidateToken);
                String candidateRole     = jwtUtil.extractUserRole(candidateToken);
                Long   candidateSchoolId = jwtUtil.extractSchoolId(candidateToken);

                if (candidateIat > bestIat) {
                    bestIat  = candidateIat;
                    token    = candidateToken;
                    userId   = candidateUserId;
                    role     = candidateRole;
                    schoolId = candidateSchoolId;
                }
            } catch (ExpiredJwtException e) {
                // Skip expired tokens — only consider valid candidates
            } catch (Exception e) {
                log.debug("Skipping malformed accessToken cookie: {}", e.getMessage());
            }
        }

        if (token == null) {
            // All accessToken cookies were expired or malformed
            log.warn("All accessToken cookies expired/invalid for request: {}", request.getRequestURI());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.getWriter().write("{\"message\": \"Token Expired\"}");
            return;
        }

        log.debug("JWT selected (iat={}) for userId={}, role={}, schoolId={}", bestIat, userId, role, schoolId);

        // Only authenticate if no context exists yet
        if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                UserDetails userDetails = userDetailsService.loadUserByUsername(userId);

                if (jwtUtil.validateToken(token, userDetails)) {
                    // Build authorities: ROLE_ + permission keys
                    List<GrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                    try {
                        List<String> permKeys = rolePermissionRepository.findPermissionKeysByRoleAndSchool(role, schoolId);
                        for (String key : permKeys) {
                            authorities.add(new SimpleGrantedAuthority(key));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to load permissions for role={}, schoolId={}: {}", role, schoolId, e.getMessage());
                    }

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null,
                                    authorities
                            );

                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    log.debug("JWT validated & security context set for userId={}", userId);

                } else {
                    log.warn("JWT validation failed for userId={}", userId);
                }
            } catch (Exception e) {
                log.error("Failed to load user details for userId={}: {}", userId, e.getMessage());
            }
        }

        SchoolContext.set(schoolId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SchoolContext.clear();
        }
    }
}
