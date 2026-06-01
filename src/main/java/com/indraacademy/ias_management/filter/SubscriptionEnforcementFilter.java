package com.indraacademy.ias_management.filter;

import com.indraacademy.ias_management.entity.SchoolEffectiveEntitlement;
import com.indraacademy.ias_management.repository.SchoolEffectiveEntitlementRepository;
import com.indraacademy.ias_management.util.SchoolContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Blocks write operations (POST, PUT, DELETE, PATCH) when a school's subscription
 * is EXPIRED. Read-only access (GET) remains available so admins can still view
 * data. Auth and subscription/payment paths are always allowed so the admin can
 * renew.
 *
 * Execution order: JwtAuthFilter → TenantValidationFilter → this filter.
 */
@Component
public class SubscriptionEnforcementFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionEnforcementFilter.class);

    @Autowired
    private SchoolEffectiveEntitlementRepository entitlementRepo;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // SUPER_ADMIN or unauthenticated — no school context, skip
        Long schoolId = SchoolContext.get();
        if (schoolId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Allow all GET/HEAD/OPTIONS — read-only access stays open
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Allow paths that must work even when expired
        String path = request.getRequestURI();
        if (isExemptPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check subscription status
        SchoolEffectiveEntitlement ent = entitlementRepo.findById(schoolId).orElse(null);
        if (ent != null && "EXPIRED".equals(ent.getSubscriptionStatus())) {
            log.info("Subscription enforcement: blocked {} {} for schoolId={} (EXPIRED)",
                    method, path, schoolId);
            response.setStatus(402); // Payment Required
            response.setContentType("application/json");
            response.getWriter().write("{\"message\": \"Your school's subscription has expired. Please renew to continue using this feature.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isExemptPath(String path) {
        return path.startsWith("/api/auth/")
                || path.startsWith("/api/school/subscription")
                || path.startsWith("/api/payments/")
                || path.startsWith("/api/public/");
    }
}
