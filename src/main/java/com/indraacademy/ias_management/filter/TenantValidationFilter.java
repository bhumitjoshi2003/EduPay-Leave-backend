package com.indraacademy.ias_management.filter;

import com.indraacademy.ias_management.service.SlugResolutionService;
import com.indraacademy.ias_management.util.SchoolContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Validates that the school subdomain in the request's Host header matches
 * the schoolId already established in the JWT (via SchoolContext).
 *
 * Execution order: JwtAuthFilter sets SchoolContext → this filter validates it.
 *
 * Skips validation for:
 * - Public / auth endpoints (no JWT, no context)
 * - Root domain requests (no subdomain) — SUPER_ADMIN or Android app
 * - Unauthenticated requests (SchoolContext is null — Spring Security handles auth separately)
 * - Localhost / non-edunexify hosts — local development
 * - Validation disabled via app.tenant.subdomain-validation=false
 */
@Component
public class TenantValidationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantValidationFilter.class);

    @Autowired
    private SlugResolutionService slugResolutionService;

    @Value("${app.base-domain:edunexify.co.in}")
    private String baseDomain;

    @Value("${app.tenant.subdomain-validation:true}")
    private boolean validationEnabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // 1. Feature flag — allows disabling in dev/test environments
        if (!validationEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. Skip public and auth paths — these have no JWT and no SchoolContext
        String path = request.getRequestURI();
        if (isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. Skip if no school context — either unauthenticated (Spring Security will
        //    reject it anyway) or SUPER_ADMIN on root domain (schoolId is null in JWT)
        Long jwtSchoolId = SchoolContext.get();
        if (jwtSchoolId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // 4. Extract subdomain from Host header
        String host = request.getServerName(); // e.g. "indraacademy.edunexify.co.in"
        String slug = extractSubdomain(host);

        // 5. No subdomain → root domain or localhost → skip validation
        //    This covers: Android app pointing to edunexify.co.in, SUPER_ADMIN, local dev
        if (slug == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // 6. Resolve slug → schoolId (Redis-cached)
        Long slugSchoolId = slugResolutionService.resolveSlugToSchoolId(slug);
        if (slugSchoolId == null) {
            log.warn("Tenant validation failed: unknown or inactive slug '{}' from host '{}'", slug, host);
            writeError(response, HttpStatus.FORBIDDEN, "Unknown school");
            return;
        }

        // 7. Cross-validate: subdomain's schoolId must match the JWT's schoolId
        if (!slugSchoolId.equals(jwtSchoolId)) {
            log.warn("Tenant mismatch detected: subdomain slug '{}' (schoolId={}) != JWT schoolId={}. Host={}",
                    slug, slugSchoolId, jwtSchoolId, host);
            writeError(response, HttpStatus.FORBIDDEN, "Forbidden");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the single-level subdomain from the host.
     * Returns null if the host is the root domain, localhost, or an IP address.
     *
     * Examples:
     *   "indraacademy.edunexify.co.in" → "indraacademy"
     *   "edunexify.co.in"              → null (root domain)
     *   "localhost"                    → null
     *   "192.168.1.1"                  → null
     */
    private String extractSubdomain(String host) {
        if (host == null) return null;

        // Strip port if present (e.g. "localhost:8080")
        int colonIdx = host.indexOf(':');
        if (colonIdx != -1) {
            host = host.substring(0, colonIdx);
        }

        // Must end with .<baseDomain> to be a school subdomain
        String suffix = "." + baseDomain;
        if (!host.endsWith(suffix)) return null;

        String subdomain = host.substring(0, host.length() - suffix.length());

        // Reject empty or nested subdomains (e.g. "a.b.edunexify.co.in")
        if (subdomain.isEmpty() || subdomain.contains(".")) return null;

        return subdomain;
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/api/auth/")
                || path.startsWith("/api/public/")
                || path.startsWith("/api/uploads/")
                || path.startsWith("/api/files/")
                || path.equals("/actuator/health");
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"message\": \"" + message + "\"}");
    }
}
