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
import com.indraacademy.ias_management.service.UserSessionService;
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

    /**
     * Endpoints reachable by a restricted first-login session (mustChangePassword
     * pending). Everything else is blocked with 403 until the password is changed.
     */
    private static final java.util.Set<String> RESTRICTED_SESSION_ALLOWLIST = java.util.Set.of(
            "/api/auth/me",
            "/api/auth/change-initial-password",
            "/api/auth/logout"
    );

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private UserSessionService userSessionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Bypass auth for public endpoints (permitAll in SecurityConfig).
        // IMPORTANT: no /api/files/* exemption lives here anymore. The one legacy endpoint that
        // ever needed it (POST /api/files/uploadEventImage) has been retired — every controller
        // under /api/files/ (FileUploadRequestController's upload-request/complete) requires
        // real authentication now. A previous version of this filter used a broad
        // path.startsWith("/api/files/") match here, which silently (and incorrectly) exempted
        // those endpoints from ever having their JWT parsed at all — see JwtAuthFilterTest and
        // the Phase 2 report for the full history. Do not reintroduce an /api/files/* exemption
        // without an explicit, exact-match reason.
        //
        // Phase 3: /api/uploads/events/images/** and /api/uploads/school-logos/** are also gone
        // — those static local-disk resource handlers were removed once every persistent upload
        // category migrated to Object Storage (see WebConfig).
        if (path.startsWith("/api/auth/login")
                || path.startsWith("/api/auth/logout")
                || path.startsWith("/api/auth/refresh-token")
                || path.startsWith("/api/auth/request-password-reset")
                || path.startsWith("/api/auth/reset-password")
                || path.startsWith("/api/public/")
                || path.startsWith("/api/webhooks/")
                || path.equals("/api/actuator/health")
                || path.startsWith("/api/actuator/health/")) {

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

        String  token             = null;
        String  userId            = null;
        String  role              = null;
        Long    schoolId          = null;
        Long    sessionId         = null;
        boolean pwdChangeRequired = false;
        long    bestIat           = Long.MIN_VALUE;

        for (Cookie c : allCookies) {
            if (!"accessToken".equals(c.getName())) continue;
            try {
                String  candidateToken             = c.getValue();
                long    candidateIat                = jwtUtil.extractIssuedAt(candidateToken).getTime();
                String  candidateUserId             = jwtUtil.extractUserId(candidateToken);
                String  candidateRole               = jwtUtil.extractUserRole(candidateToken);
                Long    candidateSchoolId           = jwtUtil.extractSchoolId(candidateToken);
                Long    candidateSessionId          = jwtUtil.extractSessionId(candidateToken);
                boolean candidatePwdChangeRequired  = jwtUtil.extractPasswordChangeRequired(candidateToken);

                if (candidateIat > bestIat) {
                    bestIat           = candidateIat;
                    token             = candidateToken;
                    userId            = candidateUserId;
                    role              = candidateRole;
                    schoolId          = candidateSchoolId;
                    sessionId         = candidateSessionId;
                    pwdChangeRequired = candidatePwdChangeRequired;
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

                if (!userDetails.isEnabled()) {
                    log.warn("Blocked request to {} for inactive userId={}", path, userId);
                    SecurityContextHolder.clearContext();
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\": \"Your account is inactive. Please contact your school administrator.\"}");
                    return;
                }

                if (jwtUtil.validateToken(token, userDetails)) {
                    // Session-aware authentication: an access token is no longer sufficient
                    // on its own — the user_session it was issued for must still be active.
                    // This is what makes revoking a session (logout, revoke-one,
                    // revoke-others, logout-all, or any account-lifecycle revocation) take
                    // effect on the VERY NEXT request, instead of only once the access
                    // token's own short-lived exp eventually elapses. A missing sessionId
                    // (a pre-migration token) is treated exactly like a revoked one — there
                    // is no indefinite fallback to the old sessionless validation; the
                    // interceptor's existing silent-refresh path is what carries a legacy
                    // token forward onto a fresh, session-backed one (see AuthController).
                    boolean sessionActive;
                    try {
                        sessionActive = sessionId != null && userSessionService.isActiveForUser(sessionId, userId);
                    } catch (Exception e) {
                        // Fail closed: if session validity cannot be established, the
                        // request is rejected — never treated as authenticated.
                        log.error("Session validation error for userId={}, sessionId={}: {}", userId, sessionId, e.getMessage());
                        sessionActive = false;
                    }
                    if (!sessionActive) {
                        log.warn("Access token rejected for userId={}: session {} is missing, revoked, or expired", userId, sessionId);
                        response.setStatus(HttpStatus.UNAUTHORIZED.value());
                        response.setContentType("application/json");
                        response.getWriter().write("{\"message\": \"Session has been revoked\"}");
                        return;
                    }

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

        // Restricted first-login session: only the password-change allowlist is reachable.
        // This is enforced from the JWT claim itself (set at login time), independent of
        // route guards on the frontend, so it cannot be bypassed by direct API calls.
        if (pwdChangeRequired && !RESTRICTED_SESSION_ALLOWLIST.contains(path)) {
            log.warn("Blocked request to {} for userId={}: password change is required", path, userId);
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"message\": \"Password change required\", \"passwordChangeRequired\": true}");
            return;
        }

        SchoolContext.set(schoolId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SchoolContext.clear();
        }
    }
}
