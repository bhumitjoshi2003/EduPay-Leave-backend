package com.indraacademy.ias_management.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.AiAttendanceReminderBatch;
import com.indraacademy.ias_management.repository.AiAttendanceReminderBatchRepository;
import com.indraacademy.ias_management.service.AttendanceReminderService;
import com.indraacademy.ias_management.service.AiReminderBatchService;
import com.indraacademy.ias_management.service.AuditService;
import com.indraacademy.ias_management.service.AuthService;
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
 * AiAttendanceWorkflowController — Spring's half of the Low-Attendance Warning LangGraph
 * workflow. Direct structural mirror of AiWorkflowController — same trust-boundary pattern,
 * same tenant-isolation-against-our-own-batch-row enforcement, same dispatch()-is-the-only-
 * Postgres-write-Python-is-never-allowed-to-do-itself rule. See that class's Javadoc for the
 * full rationale, not repeated here.
 */
@RestController
@RequestMapping("/api/ai/workflows/attendance-reminders")
@PreAuthorize("isAuthenticated()")
public class AiAttendanceWorkflowController {

    private static final Logger log = LoggerFactory.getLogger(AiAttendanceWorkflowController.class);
    private static final int MAX_STORED_OUTCOMES = 50;

    @Value("${ai.service.url:http://localhost:8001}")
    private String aiServiceUrl;

    @Value("${ai.internal.secret}")
    private String aiInternalSecret;

    @Autowired private AuthService authService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private AiAttendanceReminderBatchRepository batchRepository;
    @Autowired private AttendanceReminderService attendanceReminderService;
    @Autowired private AiReminderBatchService batchService;
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
        Object thresholdRaw = body.get("threshold");
        double threshold = thresholdRaw instanceof Number ? ((Number) thresholdRaw).doubleValue() : 75.0;

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
        payload.put("threshold", threshold);
        payload.put("user", userCtx);
        payload.put("accessToken", accessTokenCookie.getValue());

        Map<String, Object> pyResponse;
        try {
            pyResponse = callPython(HttpMethod.POST, "/workflows/attendance-reminders/start", payload);
        } catch (HttpStatusCodeException e) {
            return forwardPythonError(e);
        } catch (Exception e) {
            log.error("AI attendance workflow start failed for userId={}: {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI workflow service is temporarily unavailable."));
        }

        String workflowId = (String) pyResponse.get("workflowId");
        if (workflowId == null || workflowId.isBlank()) {
            log.error("AI attendance workflow start: Python response missing workflowId for userId={}", userId);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "AI service returned an unexpected response."));
        }

        // Persisted at START, not approval — the row this and every later call tenant-checks
        // against, with zero cross-service round trip, and the idempotency anchor for dispatch().
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> students = (List<Map<String, Object>>) pyResponse.getOrDefault("students", List.of());
        List<String> studentIds = students.stream().map(s -> (String) s.get("studentId")).collect(Collectors.toList());

        AiAttendanceReminderBatch batch = new AiAttendanceReminderBatch();
        batch.setWorkflowId(workflowId);
        batch.setSchoolId(schoolId);
        batch.setAdminUserId(userId);
        batch.setSession(session);
        batch.setClassName(className);
        batch.setThreshold(threshold);
        batch.setStatus(AiReminderBatchService.PENDING_APPROVAL);
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

        AiAttendanceReminderBatch batch = batchRepository.findByWorkflowId(workflowId).orElse(null);
        if (batch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown workflow."));
        }
        if (!Objects.equals(batch.getSchoolId(), schoolId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This workflow does not belong to your school."));
        }

        // A rejected batch is closed for good. LangGraph already refuses to resume past a
        // terminal state, but refusing here too means the guarantee holds even if the checkpoint
        // has expired from Redis or is ever bypassed — Postgres is the durable record.
        // Only an APPROVAL is refused. Re-sending the same rejection stays idempotent: it falls
        // through to the graph, which replays the stored rejected state as a normal 200 — a
        // retried reject (double-click, network retry) is not a conflict.
        if (AiReminderBatchService.REJECTED.equals(batch.getStatus()) && !"rejected".equals(decision)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "This batch was rejected and can no longer be approved."));
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
            Map<String, Object> pyResponse = callPython(HttpMethod.POST, "/workflows/attendance-reminders/" + workflowId + "/resume", payload);
            // Persist the rejection only once the graph confirms it actually took effect — the
            // graph stays authoritative for workflow state, Spring just records the outcome
            // durably, exactly as dispatch() already does for a send.
            if ("rejected".equals(decision) && "rejected".equals(pyResponse.get("status"))) {
                batchService.markRejected(() -> batchRepository.findByWorkflowIdForUpdate(workflowId), batchRepository);
            }
            return ResponseEntity.ok(pyResponse);
        } catch (HttpStatusCodeException e) {
            return forwardPythonError(e);
        } catch (Exception e) {
            log.error("AI attendance workflow resume failed for workflowId={}: {}", workflowId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI workflow service is temporarily unavailable."));
        }
    }

    // ─── Status ────────────────────────────────────────────────────────────────

    @GetMapping("/{workflowId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> status(@PathVariable String workflowId) {
        Long schoolId = securityUtil.getSchoolId();

        AiAttendanceReminderBatch batch = batchRepository.findByWorkflowId(workflowId).orElse(null);
        if (batch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown workflow."));
        }
        if (!Objects.equals(batch.getSchoolId(), schoolId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This workflow does not belong to your school."));
        }

        try {
            Map<String, Object> pyResponse = callPython(HttpMethod.GET, "/workflows/attendance-reminders/" + workflowId, null);
            return ResponseEntity.ok(pyResponse);
        } catch (HttpStatusCodeException e) {
            return forwardPythonError(e);
        } catch (Exception e) {
            log.error("AI attendance workflow status fetch failed for workflowId={}: {}", workflowId, e.getMessage());
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

        AiAttendanceReminderBatch batch = batchRepository.findByWorkflowIdForUpdate(workflowId).orElse(null);
        if (batch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown workflow."));
        }
        if (!Objects.equals(batch.getSchoolId(), schoolId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This workflow does not belong to your school."));
        }

        // Idempotent short-circuit — the row-lock above serializes concurrent dispatch
        // calls for the same workflowId, so this check is race-free: belt-and-suspenders
        // alongside Python's own resume-lock, in case that ever has a bug or is bypassed.
        // A rejected batch must never send. Refused rather than replayed: unlike an
        // already-sent batch there is no stored outcome to return, and answering "0 sent, 0
        // failed" would read as a successful no-op send instead of a refusal.
        if (AiReminderBatchService.REJECTED.equals(batch.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "This batch was rejected and cannot be dispatched."));
        }

        if (AiReminderBatchService.SEND_ATTEMPTED_STATUSES.contains(batch.getStatus())) {
            return ResponseEntity.ok(dispatchResponseFrom(batch));
        }

        @SuppressWarnings("unchecked")
        List<String> studentIds = (List<String>) body.get("studentIds");
        String session = (String) body.get("session");
        if (studentIds == null || studentIds.isEmpty() || session == null || session.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "studentIds and session are required"));
        }

        Map<String, String> outcomes = attendanceReminderService
                .sendAttendanceReminderEmailsWithOutcomes(studentIds, session, workflowId);
        int sent = 0, failed = 0;
        for (String outcome : outcomes.values()) {
            if ("sent".equals(outcome)) sent++; else failed++;
        }

        batch.setStatus(failed == 0 ? AiReminderBatchService.SENT
                : (sent == 0 ? AiReminderBatchService.FAILED : AiReminderBatchService.PARTIALLY_SENT));
        batch.setSentCount(sent);
        batch.setFailedCount(failed);
        batch.setOutcomes(toJson(capOutcomes(outcomes)));
        batch.setCompletedAt(LocalDateTime.now());
        batchRepository.save(batch);

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "SEND_ATTENDANCE_REMINDER_AI_WORKFLOW",
                "Attendance",
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

    private Map<String, Object> dispatchResponseFrom(AiAttendanceReminderBatch batch) {
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
            log.warn("Failed to serialize AI attendance workflow field: {}", e.getMessage());
            return "[]";
        }
    }

    private List<Map<String, Object>> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize AI attendance workflow field: {}", e.getMessage());
            return List.of();
        }
    }
}
