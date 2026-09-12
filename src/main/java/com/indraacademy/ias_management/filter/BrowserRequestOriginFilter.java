package com.indraacademy.ias_management.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CSRF protection for the browser-cookie authentication model.
 *
 * Browser unsafe requests always carry an Origin header. We accept only Edunexify web origins
 * and the explicitly configured native/development origins. A school subdomain must also agree
 * with X-School-Slug, preventing one tenant subdomain from using domain-scoped auth cookies to
 * act as another tenant. Requests without Origin remain available to non-browser clients; they
 * cannot be produced by a cross-site browser form/fetch without an Origin header.
 */
@Component
public class BrowserRequestOriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Value("${app.base-domain:edunexify.co.in}")
    private String baseDomain;

    @Value("${frontend.url}")
    private String frontendUrl;

    @Value("${cors.additional-origins:capacitor://localhost,http://localhost,https://localhost}")
    private String additionalOrigins;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod())
                || request.getRequestURI().startsWith("/api/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!isAllowed(origin, request.getHeader("X-School-Slug"))) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Untrusted request origin\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    boolean isAllowed(String origin, String requestedSlug) {
        Set<String> explicit = Arrays.stream((additionalOrigins + "," + frontendUrl).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        if (explicit.contains(origin)) return true;

        try {
            URI uri = URI.create(origin);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return false;
            String host = uri.getHost().toLowerCase();
            if (host.equals(baseDomain)) return true;
            String suffix = "." + baseDomain;
            if (!host.endsWith(suffix)) return false;
            String originSlug = host.substring(0, host.length() - suffix.length());
            return !originSlug.isBlank() && !originSlug.contains(".")
                    && requestedSlug != null && originSlug.equalsIgnoreCase(requestedSlug.trim());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
