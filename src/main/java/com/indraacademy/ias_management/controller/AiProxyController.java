package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.repository.AdminRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.WebUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AiProxyController — secure gateway between Angular and the Python AI service.
 *
 * Why does Spring Boot proxy this instead of Angular calling Python directly?
 *
 * 1. Auth: Spring Boot's JwtAuthFilter already validates the accessToken cookie
 *    on every request. This endpoint inherits that — no extra auth work needed.
 *
 * 2. Security: The Python service is internal-only. By routing through Spring Boot
 *    we ensure it's never reachable from the outside, even if a port is misconfigured.
 *
 * 3. Context: Spring Boot extracts the verified userId, role, and schoolId from
 *    the SecurityContext (not from request parameters the client could tamper with)
 *    and forwards them to Python. Python trusts this context completely.
 *
 * 4. Token forwarding: Spring Boot reads the raw accessToken cookie value and passes
 *    it to Python. Python forwards it when calling Spring Boot APIs (attendance, fees,
 *    etc.) — this means ALL existing @PreAuthorize checks and schoolId scoping still
 *    apply, exactly as if the Angular frontend made those calls directly.
 */
@RestController
@RequestMapping("/api/ai")
@PreAuthorize("isAuthenticated()")
public class AiProxyController {

    private static final Logger log = LoggerFactory.getLogger(AiProxyController.class);

    /** URL of the Python FastAPI service. Override via AI_SERVICE_URL env var in prod. */
    @Value("${ai.service.url:http://localhost:8001}")
    private String aiServiceUrl;

    /**
     * Shared secret between Spring Boot and the Python service.
     * Python rejects any request missing or mismatching this header.
     * Must be set via AI_INTERNAL_SECRET env var — no default, fails fast if missing.
     */
    @Value("${ai.internal.secret}")
    private String aiInternalSecret;

    @Autowired private AuthService authService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private AdminRepository adminRepository;

    // RestTemplate is fine here — calls are infrequent and latency-bound by LLM anyway.
    private final RestTemplate restTemplate = new RestTemplate();

    @PostMapping("/chat")
    public ResponseEntity<?> chat(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {

        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        // conversationId scopes short-term memory in the Python service, keyed together
        // with the trusted schoolId/userId below. It never crosses tenants or users even
        // if tampered with, but we still constrain its shape since it flows into a Redis
        // key downstream.
        String conversationId = body.get("conversationId");
        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "conversationId is required"));
        }
        if (conversationId.length() > 100 || !conversationId.matches("[A-Za-z0-9_-]+")) {
            return ResponseEntity.badRequest().body(Map.of("error", "conversationId is invalid"));
        }

        // These values come from the SecurityContext, populated by JwtAuthFilter
        // after validating the JWT. The client cannot forge these.
        String userId = authService.getUserId();
        String role = authService.getRole();
        Long schoolId = securityUtil.getSchoolId();

        // Read the raw accessToken cookie. JwtAuthFilter already validated it,
        // so we know it's present and valid. Python will forward it when calling
        // our own APIs so those endpoints run their own auth checks normally.
        jakarta.servlet.http.Cookie accessTokenCookie = WebUtils.getCookie(request, "accessToken");
        if (accessTokenCookie == null) {
            // Should not happen — JwtAuthFilter would have rejected the request first.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Access token missing"));
        }

        // Resolve display name and class from the DB — same pattern as AuthController.
        String name = resolveName(userId, role, schoolId);
        String className = resolveClassName(userId, role, schoolId);

        // Build the payload for the Python AI service.
        Map<String, Object> userCtx = new LinkedHashMap<>();
        userCtx.put("userId", userId);
        userCtx.put("role", role);
        userCtx.put("schoolId", schoolId);
        userCtx.put("name", name);
        userCtx.put("className", className);

        Map<String, Object> aiPayload = new LinkedHashMap<>();
        aiPayload.put("message", message);
        aiPayload.put("conversationId", conversationId);
        aiPayload.put("user", userCtx);
        aiPayload.put("accessToken", accessTokenCookie.getValue());

        // Set the internal secret header so Python knows this came from us.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Secret", aiInternalSecret);

        HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(aiPayload, headers);

        try {
            ResponseEntity<Map> aiResponse = restTemplate.postForEntity(
                    aiServiceUrl + "/chat",
                    httpEntity,
                    Map.class
            );
            log.info("AI copilot request fulfilled for userId={}, role={}", userId, role);
            return ResponseEntity.ok(aiResponse.getBody());

        } catch (Exception e) {
            log.error("AI service call failed for userId={}: {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI Copilot is temporarily unavailable. Please try again later."));
        }
    }

    // ─── Helpers (same pattern as AuthController) ─────────────────────────────

    private String resolveName(String userId, String role, Long schoolId) {
        try {
            return switch (role) {
                case Role.STUDENT -> studentRepository.findByStudentIdAndSchoolId(userId, schoolId)
                        .map(s -> s.getName()).orElse(null);
                case Role.TEACHER -> teacherRepository.findByTeacherIdAndSchoolId(userId, schoolId)
                        .map(t -> t.getName()).orElse(null);
                default -> (schoolId != null)
                        ? adminRepository.findByAdminIdAndSchoolId(userId, schoolId)
                                .map(a -> a.getName()).orElse(null)
                        : adminRepository.findById(userId).map(a -> a.getName()).orElse(null);
            };
        } catch (Exception e) {
            log.warn("Could not resolve name for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private String resolveClassName(String userId, String role, Long schoolId) {
        try {
            return switch (role) {
                case Role.STUDENT -> studentRepository.findByStudentIdAndSchoolId(userId, schoolId)
                        .map(s -> s.getClassName()).orElse(null);
                case Role.TEACHER -> teacherRepository.findByTeacherIdAndSchoolId(userId, schoolId)
                        .map(t -> t.getClassTeacher()).orElse(null);
                default -> null;
            };
        } catch (Exception e) {
            log.warn("Could not resolve className for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }
}
