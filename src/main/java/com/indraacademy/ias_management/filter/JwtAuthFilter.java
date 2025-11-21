package com.indraacademy.ias_management.filter;

import com.indraacademy.ias_management.util.JwtUtil;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Bypass auth for public endpoints
        if (path.startsWith("/api/auth/login")
                || path.startsWith("/api/auth/refresh-token")
                || path.startsWith("/api/auth/request-password-reset")
                || path.startsWith("/api/auth/reset-password")) {

            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {

            String token = authHeader.substring(7);
            String userId;
            String role;

            try {
                userId = jwtUtil.extractUserId(token);
                role = jwtUtil.extractUserRole(token);

                log.debug("JWT extracted for userId={}, role={}", userId, role);

            } catch (ExpiredJwtException e) {
                log.warn("Expired JWT token for request: {}", request.getRequestURI());
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.getWriter().write("{\"message\": \"Token Expired\"}");
                return;
            } catch (Exception e) {
                log.error("Invalid JWT token: {}", e.getMessage());
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                return;
            }

            // Only authenticate if no context exists yet
            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    UserDetails userDetails = userDetailsService.loadUserByUsername(userId);

                    if (jwtUtil.validateToken(token, userDetails)) {
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        userDetails, null,
                                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
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
        }

        filterChain.doFilter(request, response);
    }
}
