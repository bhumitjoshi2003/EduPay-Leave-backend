package com.indraacademy.ias_management.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.repository.AdminRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.observability.UnexpectedErrorReporter;
import com.indraacademy.ias_management.filter.RequestIdFilter;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.TeacherClassScopeService;
import com.indraacademy.ias_management.service.TeacherClassScopeService.TeacherScope;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.WebUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
    @Autowired private TeacherClassScopeService teacherClassScopeService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UnexpectedErrorReporter errorReporter;

    // RestTemplate is fine here — calls are infrequent and latency-bound by LLM anyway.
    private final RestTemplate restTemplate = new RestTemplate();

    // java.net.http.HttpClient (not RestTemplate) for /chat/stream: RestTemplate
    // reads the whole response body before returning, which defeats streaming.
    // HttpClient with BodyHandlers.ofInputStream() hands back a live InputStream
    // we can copy from as bytes arrive. Safe to share across requests/threads.
    //
    // version(HTTP_1_1) is required, not cosmetic: HttpClient defaults to attempting
    // an HTTP/2 (h2c) upgrade even over plain HTTP, and uvicorn only speaks HTTP/1.1.
    // Against that mismatch the client silently drops the request body — Python saw
    // a completely empty body (FastAPI 422 "field required", input: null) even though
    // this code was sending one. Reproduced and confirmed via a standalone HttpClient
    // call; forcing HTTP/1.1 fixed it immediately.
    private final HttpClient streamingHttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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

        // Resolve display name and class scope from the DB — same pattern as AuthController.
        // The class scope (class AND section) comes from TeacherClassScopeService, so it can
        // never advertise wider reach than the REST endpoints the AI's tools call back into.
        String name = resolveName(userId, role, schoolId);
        ClassContext classContext = resolveClassContext(userId, role, schoolId);

        // Build the payload for the Python AI service.
        Map<String, Object> userCtx = new LinkedHashMap<>();
        userCtx.put("userId", userId);
        userCtx.put("role", role);
        userCtx.put("schoolId", schoolId);
        userCtx.put("name", name);
        putClassContext(userCtx, classContext);

        Map<String, Object> aiPayload = new LinkedHashMap<>();
        aiPayload.put("message", message);
        aiPayload.put("conversationId", conversationId);
        aiPayload.put("user", userCtx);
        aiPayload.put("accessToken", accessTokenCookie.getValue());

        // Set the internal secret header so Python knows this came from us.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Secret", aiInternalSecret);
        String requestId = org.slf4j.MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) headers.set(RequestIdFilter.HEADER, requestId);

        HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(aiPayload, headers);

        try {
            ResponseEntity<Map> aiResponse = restTemplate.postForEntity(
                    aiServiceUrl + "/chat",
                    httpEntity,
                    Map.class
            );
            log.info("AI copilot request fulfilled");
            return ResponseEntity.ok(aiResponse.getBody());

        } catch (Exception e) {
            log.error("AI service call failed", e);
            errorReporter.report("ai.chat", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI Copilot is temporarily unavailable. Please try again later."));
        }
    }

    /**
     * Streaming counterpart to /chat. Same auth, same context-building, same
     * Python endpoint family (Python's /chat/stream) — the only difference is
     * that the Python response body is copied to the client as bytes arrive,
     * instead of being read into a Map first. RestTemplate can't do this (it
     * buffers the full body before returning), so this uses java.net.http.HttpClient
     * + StreamingResponseBody instead, which Spring MVC runs on a separate async
     * thread and flushes to the client as we write to it — no WebFlux required.
     *
     * Tool-call turns produce no user-facing content in Python's stream (only
     * the final turn does), so nothing extra is needed here to "hide" tool
     * calls — we're a dumb byte pipe, Python already only streams the final answer.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<StreamingResponseBody> chatStream(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {

        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(out -> out.write(
                    "message is required".getBytes(StandardCharsets.UTF_8)));
        }

        String conversationId = body.get("conversationId");
        if (conversationId == null || conversationId.isBlank()
                || conversationId.length() > 100 || !conversationId.matches("[A-Za-z0-9_-]+")) {
            return ResponseEntity.badRequest().body(out -> out.write(
                    "conversationId is required and must be alphanumeric".getBytes(StandardCharsets.UTF_8)));
        }

        String userId = authService.getUserId();
        String role = authService.getRole();
        Long schoolId = securityUtil.getSchoolId();

        jakarta.servlet.http.Cookie accessTokenCookie = WebUtils.getCookie(request, "accessToken");
        if (accessTokenCookie == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(out -> out.write(
                    "Access token missing".getBytes(StandardCharsets.UTF_8)));
        }

        Map<String, Object> userCtx = new LinkedHashMap<>();
        userCtx.put("userId", userId);
        userCtx.put("role", role);
        userCtx.put("schoolId", schoolId);
        userCtx.put("name", resolveName(userId, role, schoolId));
        putClassContext(userCtx, resolveClassContext(userId, role, schoolId));

        Map<String, Object> aiPayload = new LinkedHashMap<>();
        aiPayload.put("message", message);
        aiPayload.put("conversationId", conversationId);
        aiPayload.put("user", userCtx);
        aiPayload.put("accessToken", accessTokenCookie.getValue());

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(aiPayload);
        } catch (Exception e) {
            log.error("Failed to serialize AI payload for userId={}: {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(out -> out.write(
                    "Failed to prepare AI request".getBytes(StandardCharsets.UTF_8)));
        }

        String correlationId = java.util.Objects.requireNonNullElseGet(
                org.slf4j.MDC.get(RequestIdFilter.MDC_KEY), () -> java.util.UUID.randomUUID().toString());

        StreamingResponseBody stream = outputStream -> {
            // Spring MVC runs StreamingResponseBody on a separate async thread, so the
            // RequestIdFilter's MDC entry (thread-local, set on the request thread) isn't
            // visible here. Re-establish it from the already-resolved correlationId so
            // errorReporter.report() below can still tag the failure with the request ID.
            org.slf4j.MDC.put(RequestIdFilter.MDC_KEY, correlationId);
            try {
                HttpRequest pythonRequest = HttpRequest.newBuilder()
                        .uri(URI.create(aiServiceUrl + "/chat/stream"))
                        .header("Content-Type", "application/json")
                        .header("X-Internal-Secret", aiInternalSecret)
                        .header(RequestIdFilter.HEADER, correlationId)
                        .timeout(Duration.ofSeconds(90))
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<InputStream> pythonResponse = streamingHttpClient.send(
                        pythonRequest, HttpResponse.BodyHandlers.ofInputStream());

                try (InputStream pythonBody = pythonResponse.body()) {
                    if (pythonResponse.statusCode() != 200) {
                        pythonBody.readAllBytes();
                        log.error("AI stream call returned status={}", pythonResponse.statusCode());
                        writeAndFlush(outputStream, "AI Copilot is temporarily unavailable. Please try again later.");
                        return;
                    }

                    copyStream(pythonBody, outputStream);
                    log.info("AI copilot stream completed");
                }

            } catch (Exception e) {
                // Full stack trace, not just getMessage() — some IOExceptions (e.g. a
                // client/proxy hangup mid-write) carry little in the message alone.
                log.error("AI stream failed", e);
                errorReporter.report("ai.stream", e);
                writeAndFlush(outputStream, "\n\n⚠️ AI Copilot is temporarily unavailable. Please try again later.");
            } finally {
                org.slf4j.MDC.remove(RequestIdFilter.MDC_KEY);
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8"))
                .body(stream);
    }

    /** Copies bytes from Python's response to the client, flushing after every chunk — no buffering. */
    private void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[512];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            out.flush();
        }
    }

    private void writeAndFlush(OutputStream out, String text) {
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
            // Client likely already disconnected — nothing more we can do.
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
            log.warn("Could not resolve AI caller display name: type={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The caller's authoritative class scope for this chat turn.
     *
     * @param className          the class the caller is scoped to, or null when they have none
     *                           usable
     * @param sectionId          the section within that class, or null when the class has no
     *                           configured sections
     * @param sectionScopeBlocked true for a TEACHER whose class HAS sections but whose own
     *                           section assignment is missing/ambiguous — they are blocked from
     *                           class data entirely until an admin resolves it
     */
    private record ClassContext(String className, Long sectionId, boolean sectionScopeBlocked) {
        static final ClassContext NONE = new ClassContext(null, null, false);
        static final ClassContext BLOCKED = new ClassContext(null, null, true);
    }

    /**
     * Resolves the class context handed to the AI service, replacing the old
     * {@code resolveClassName}, which returned a TEACHER's raw {@code classTeacher} string.
     *
     * <p><b>Why this changed.</b> A class name alone stopped being an authorization boundary
     * once a class can be split into sections — the class-teacher of Class 12 / Science and of
     * Class 12 / Commerce both have {@code classTeacher = "12"}. The section now comes from
     * {@link TeacherClassScopeService#resolveOwnScope}, the same primitive every class-scoped
     * REST endpoint uses, so this context can never disagree with what those endpoints enforce.
     *
     * <p><b>What this is and isn't.</b> The actual enforcement is NOT here and must never be:
     * the AI service's tools call back into our own REST endpoints with the teacher's token,
     * and those endpoints (AttendanceController, LeaveController, MarkController, …) re-resolve
     * the teacher's section themselves and refuse anything outside it. This method only makes
     * the context we advertise match that reality, so the model isn't told it has whole-class
     * reach that Spring will then refuse. Concretely: a teacher blocked by a missing section
     * assignment is given a null className, which makes the class-scoped tools decline up front
     * instead of promising data and returning a bare 403.
     *
     * <p>{@code sectionId}/{@code sectionScopeBlocked} are forwarded for the AI service to use
     * once its own {@code UserContext} schema declares them — until then Pydantic drops them as
     * unknown fields. They are context, never authority: Python must never decide a teacher's
     * section from them, only ever be told what Spring already resolved.
     */
    private ClassContext resolveClassContext(String userId, String role, Long schoolId) {
        try {
            if (Role.STUDENT.equals(role)) {
                return studentRepository.findByStudentIdAndSchoolId(userId, schoolId)
                        .map(s -> new ClassContext(s.getClassName(), s.getSectionId(), false))
                        .orElse(ClassContext.NONE);
            }
            if (Role.TEACHER.equals(role)) {
                TeacherScope scope = teacherClassScopeService.resolveOwnScope(userId, schoolId);
                if (!scope.hasClassResponsibility()) {
                    return ClassContext.NONE;
                }
                if (scope.sectionRequiredButMissing()) {
                    // Never advertise the class: this teacher's authority over it is ambiguous,
                    // and every class endpoint will refuse them until an admin resolves it.
                    log.warn("AI chat context reports no class scope because the caller's section is unresolved");
                    return ClassContext.BLOCKED;
                }
                return new ClassContext(scope.className(), scope.sectionId(), false);
            }
            return ClassContext.NONE;
        } catch (Exception e) {
            log.warn("Could not resolve AI caller class context: type={}", e.getClass().getSimpleName());
            return ClassContext.NONE;
        }
    }

    /** Adds the resolved class scope to the user context sent to the AI service. */
    private void putClassContext(Map<String, Object> userCtx, ClassContext ctx) {
        userCtx.put("className", ctx.className());
        userCtx.put("sectionId", ctx.sectionId());
        userCtx.put("sectionScopeBlocked", ctx.sectionScopeBlocked());
    }
}
