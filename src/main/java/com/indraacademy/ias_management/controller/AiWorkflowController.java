package com.indraacademy.ias_management.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.AiFeeReminderBatch;
import com.indraacademy.ias_management.repository.AiFeeReminderBatchRepository;
import com.indraacademy.ias_management.service.AuditService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.FeeReminderService;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.WebUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AiWorkflowController — Spring's half of the Fee Defaulter Reminder LangGraph workflow.
 *
 * Same trust-boundary pattern as AiProxyController: userId/role/schoolId always come from
 * the verified SecurityContext, never the request body; the accessToken cookie is read
 * fresh from *this* request on every call (including approve/reject, which may arrive
 * long after the token that started the workflow has expired) and forwarded to Python,
 * which forwards it again to whichever Spring endpoint it needs — so all existing
 * @PreAuthorize/schoolId scoping applies exactly as if Angular called those directly.
 *
 * Tenant isolation on approve/reject/status is enforced HERE, against the
 * ai_fee_reminder_batch row this controller itself wrote at start time — not by asking
 * Python, which would just be trusting Python's copy of the same claim one hop later for
 * no benefit. The dispatch() endpoint is the one write Python is never allowed to do
 * itself (see the class-level rule in CLAUDE.md: Python never touches Postgres) — it's
 * called by Python's send_reminders node, using the real forwarded admin JWT, and is the
 * ultimate idempotency backstop: even if Python's own resume-lock had a bug, this endpoint
 * still can't send the same batch twice, because it checks/updates one uniquely-keyed row
 * inside a single row-locked transaction before ever calling EmailService.
 */
@RestController
@RequestMapping("/api/ai/workflows/fee-reminders")
@PreAuthorize("isAuthenticated()")
public class AiWorkflowController {

    private static final Logger log = LoggerFactory.getLogger(AiWorkflowController.class);
    private static final int MAX_STORED_OUTCOMES = 50;
    private static final List<String> TERMINAL_STATUSES = List.of("SENT", "PARTIALLY_SENT", "FAILED");

    @Value("${ai.service.url:http://localhost:8001}")
    private String aiServiceUrl;

    @Value("${ai.internal.secret}")
    private String aiInternalSecret;

    @Autowired private AuthService authService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private AiFeeReminderBatchRepository batchRepository;
    @Autowired private FeeReminderService feeReminderService;
    @Autowired private AuditService auditService;
    @Autowired private ObjectMapper objectMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    // ─── Start ─────────────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String session = (String) body.get("session");
        if (session == null || session.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "session is required"));
        }
        String className = (String) body.get("className");

        String userId = authService.getUserId();
        String role = authService.getRole();
        Long schoolId = securityUtil.getSchoolId();

        Cookie accessTokenCookie = WebUtils.getCookie(request, "accessToken");
        if (accessTokenCookie == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Access token missing"));
        }

        Map<String, Object> userCtx = new LinkedHashMap<>();
        userCtx.put("userId", userId);
        userCtx.put("role", role);
        userCtx.put("schoolId", schoolId);
        userCtx.put("name", null);
        userCtx.put("className", null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session", session);
        payload.put("className", className);
        payload.put("user", userCtx);
        payload.put("accessToken", accessTokenCookie.getValue());

        Map<String, Object> pyResponse;
        try {
            pyResponse = callPython(HttpMethod.POST, "/workflows/fee-reminders/start", payload);
        } catch (HttpStatusCodeException e) {
            return forwardPythonError(e);
        } catch (Exception e) {
            log.error("AI workflow start failed for userId={}: {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI workflow service is temporarily unavailable."));
        }

        String workflowId = (String) pyResponse.get("workflowId");
        if (workflowId == null || workflowId.isBlank()) {
            log.error("AI workflow start: Python response missing workflowId for userId={}", userId);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "AI service returned an unexpected response."));
        }

        // Persisted at START, not approval — the row this and every later call tenant-checks
        // against, with zero cross-service round trip, and the idempotency anchor for dispatch().
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> defaulters = (List<Map<String, Object>>) pyResponse.getOrDefault("defaulters", List.of());
        List<String> studentIds = defaulters.stream().map(d -> (String) d.get("studentId")).collect(Collectors.toList());

        AiFeeReminderBatch batch = new AiFeeReminderBatch();
        batch.setWorkflowId(workflowId);
        batch.setSchoolId(schoolId);
        batch.setAdminUserId(userId);
        batch.setSession(session);
        batch.setClassName(className);
        batch.setStatus("PENDING_APPROVAL");
        batch.setStudentIds(toJson(studentIds));
        batchRepository.save(batch);

        return ResponseEntity.ok(pyResponse);
    }

    // ─── Approve / Reject ─────────────────────────────────────────────────────

    @PostMapping("/{workflowId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> approve(@PathVariable String workflowId, HttpServletRequest request) {
        return resume(workflowId, "approved", request);
    }

    @PostMapping("/{workflowId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> reject(@PathVariable String workflowId, HttpServletRequest request) {
        return resume(workflowId, "rejected", request);
    }

    private ResponseEntity<?> resume(String workflowId, String decision, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        String userId = authService.getUserId();

        AiFeeReminderBatch batch = batchRepository.findByWorkflowId(workflowId).orElse(null);
        if (batch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown workflow."));
        }
        if (!Objects.equals(batch.getSchoolId(), schoolId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This workflow does not belong to your school."));
        }

        Cookie accessTokenCookie = WebUtils.getCookie(request, "accessToken");
        if (accessTokenCookie == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Access token missing"));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", decision);
        payload.put("schoolId", schoolId);
        payload.put("adminUserId", userId);
        payload.put("accessToken", accessTokenCookie.getValue());

        try {
            Map<String, Object> pyResponse = callPython(HttpMethod.POST, "/workflows/fee-reminders/" + workflowId + "/resume", payload);
            return ResponseEntity.ok(pyResponse);
        } catch (HttpStatusCodeException e) {
            return forwardPythonError(e);
        } catch (Exception e) {
            log.error("AI workflow resume failed for workflowId={}: {}", workflowId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI workflow service is temporarily unavailable."));
        }
    }

    // ─── Status ────────────────────────────────────────────────────────────────

    @GetMapping("/{workflowId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> status(@PathVariable String workflowId) {
        Long schoolId = securityUtil.getSchoolId();

        AiFeeReminderBatch batch = batchRepository.findByWorkflowId(workflowId).orElse(null);
        if (batch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown workflow."));
        }
        if (!Objects.equals(batch.getSchoolId(), schoolId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This workflow does not belong to your school."));
        }

        try {
            Map<String, Object> pyResponse = callPython(HttpMethod.GET, "/workflows/fee-reminders/" + workflowId, null);
            return ResponseEntity.ok(pyResponse);
        } catch (HttpStatusCodeException e) {
            return forwardPythonError(e);
        } catch (Exception e) {
            log.error("AI workflow status fetch failed for workflowId={}: {}", workflowId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI workflow service is temporarily unavailable."));
        }
    }

    // ─── Dispatch (called only by Python's send_reminders node, never Angular) ────

    @PostMapping("/{workflowId}/dispatch")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @Transactional
    public ResponseEntity<?> dispatch(
            @PathVariable String workflowId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        Long schoolId = securityUtil.getSchoolId();

        AiFeeReminderBatch batch = batchRepository.findByWorkflowIdForUpdate(workflowId).orElse(null);
        if (batch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown workflow."));
        }
        if (!Objects.equals(batch.getSchoolId(), schoolId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This workflow does not belong to your school."));
        }

        // Idempotent short-circuit — the row-lock above serializes concurrent dispatch
        // calls for the same workflowId, so this check is race-free: belt-and-suspenders
        // alongside Python's own resume-lock, in case that ever has a bug or is bypassed.
        if (TERMINAL_STATUSES.contains(batch.getStatus())) {
            return ResponseEntity.ok(dispatchResponseFrom(batch));
        }

        @SuppressWarnings("unchecked")
        List<String> studentIds = (List<String>) body.get("studentIds");
        String session = (String) body.get("session");
        if (studentIds == null || studentIds.isEmpty() || session == null || session.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "studentIds and session are required"));
        }

        Map<String, String> outcomes = feeReminderService.sendReminderEmailsWithOutcomes(studentIds, session);
        int sent = 0, failed = 0;
        for (String outcome : outcomes.values()) {
            if ("sent".equals(outcome)) sent++; else failed++;
        }

        batch.setStatus(failed == 0 ? "SENT" : (sent == 0 ? "FAILED" : "PARTIALLY_SENT"));
        batch.setSentCount(sent);
        batch.setFailedCount(failed);
        batch.setOutcomes(toJson(capOutcomes(outcomes)));
        batch.setCompletedAt(LocalDateTime.now());
        batchRepository.save(batch);

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "SEND_FEE_REMINDER_AI_WORKFLOW",
                "StudentFees",
                null,
                null,
                "AI workflow " + workflowId + ": " + sent + " sent, " + failed + " failed, session " + session,
                request.getRemoteAddr()
        );

        return ResponseEntity.ok(dispatchResponseFrom(batch));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> callPython(HttpMethod method, String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Secret", aiInternalSecret);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange(aiServiceUrl + path, method, entity, Map.class);
        return response.getBody();
    }

    /** Python's FastAPI HTTPException bodies are {"detail": "..."} — forward the real
     * status code (403/404/409/etc) and message instead of flattening everything to 503. */
    private ResponseEntity<?> forwardPythonError(HttpStatusCodeException e) {
        String detail;
        try {
            Map<String, Object> body = objectMapper.readValue(e.getResponseBodyAsString(), new TypeReference<>() {});
            detail = String.valueOf(body.getOrDefault("detail", e.getMessage()));
        } catch (Exception parseFailure) {
            detail = e.getMessage();
        }
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", detail));
    }

    private List<Map<String, String>> capOutcomes(Map<String, String> outcomes) {
        return outcomes.entrySet().stream()
                .limit(MAX_STORED_OUTCOMES)
                .map(e -> Map.of("studentId", e.getKey(), "status", e.getValue()))
                .collect(Collectors.toList());
    }

    private Map<String, Object> dispatchResponseFrom(AiFeeReminderBatch batch) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sentCount", batch.getSentCount());
        result.put("failedCount", batch.getFailedCount());
        result.put("outcomes", fromJson(batch.getOutcomes()));
        return result;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize AI workflow field: {}", e.getMessage());
            return "[]";
        }
    }

    private List<Map<String, Object>> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize AI workflow field: {}", e.getMessage());
            return List.of();
        }
    }
}
