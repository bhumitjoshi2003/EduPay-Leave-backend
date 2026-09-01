package com.indraacademy.ias_management.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.AiLeaveDecisionBatch;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.repository.AiLeaveDecisionBatchRepository;
import com.indraacademy.ias_management.service.AiReminderBatchService;
import com.indraacademy.ias_management.service.AuditService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.LeaveDecisionService;
import com.indraacademy.ias_management.service.PermissionService;
import com.indraacademy.ias_management.service.TeacherClassScopeService;
import com.indraacademy.ias_management.service.TeacherClassScopeService.TeacherScope;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AiLeaveWorkflowController — Spring's half of the Leave Decision LangGraph workflow.
 * Structural mirror of AiTeacherAttendanceWorkflowController: same trust boundary, same
 * tenant-check-against-our-own-batch-row rule, same dispatch()-is-the-only-mutation rule, and the
 * same shared lifecycle via AiReminderBatchService (so a rejected leave batch is durably REJECTED
 * and can never later be approved or dispatched).
 *
 * <p><b>This workflow carries a risk the reminder workflows structurally could not.</b> A reminder
 * batch has no recipient parameter at all — the backend derives the list, so the model cannot
 * choose who is contacted. Here the model necessarily proposes specific leave IDs, so that
 * guarantee is replaced by defence in depth:
 * <ol>
 *   <li>role must be ADMIN or TEACHER, AND the role must actually hold LEAVE_APPROVE;</li>
 *   <li>a TEACHER batch is pinned to that teacher's own class AND section (resolved via
 *       {@link com.indraacademy.ias_management.service.TeacherClassScopeService}, never from the
 *       request) at start and re-verified on every later call, even though the underlying leave
 *       API is only school-scoped;</li>
 *   <li>every proposed ID is re-read server-side at apply time and skipped unless it is still
 *       PENDING and inside the batch's class AND the acting teacher's own section (see
 *       LeaveDecisionService);</li>
 *   <li>nothing is applied until a human approves the card.</li>
 * </ol>
 * The model's IDs are therefore a <i>proposal</i>, never an instruction.
 */
@RestController
@RequestMapping("/api/ai/workflows/leave-decisions")
@PreAuthorize("isAuthenticated()")
public class AiLeaveWorkflowController {

    private static final Logger log = LoggerFactory.getLogger(AiLeaveWorkflowController.class);
    private static final int MAX_STORED_OUTCOMES = 50;
    private static final int MAX_LEAVES_PER_BATCH = 50;
    /** Seeded and surfaced at login, but historically never enforced server-side — enforced here. */
    static final String LEAVE_APPROVE_PERMISSION = "LEAVE_APPROVE";

    @Value("${ai.service.url:http://localhost:8001}")
    private String aiServiceUrl;

    @Value("${ai.internal.secret}")
    private String aiInternalSecret;

    @Autowired private AuthService authService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private TeacherClassScopeService teacherClassScopeService;
    @Autowired private PermissionService permissionService;
    @Autowired private AiLeaveDecisionBatchRepository batchRepository;
    @Autowired private LeaveDecisionService leaveDecisionService;
    @Autowired private AiReminderBatchService batchService;
    @Autowired private AuditService auditService;
    @Autowired private ObjectMapper objectMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    // ─── Start ─────────────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String userId = authService.getUserId();
        String role = authService.getRole();
        Long schoolId = securityUtil.getSchoolId();

        ResponseEntity<?> permissionError = requireLeaveApprovePermission(role, schoolId);
        if (permissionError != null) return permissionError;

        String decision = body.get("decision") instanceof String s ? s.toUpperCase() : null;
        if (!LeaveStatus.APPROVED.name().equals(decision) && !LeaveStatus.REJECTED.name().equals(decision)) {
            return ResponseEntity.badRequest().body(Map.of("error", "decision must be APPROVED or REJECTED"));
        }

        List<Long> leaveIds = parseLeaveIds(body.get("leaveIds"));
        if (leaveIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "leaveIds must contain at least one leave request id"));
        }
        if (leaveIds.size() > MAX_LEAVES_PER_BATCH) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "A single batch is limited to " + MAX_LEAVES_PER_BATCH + " leave requests."));
        }

        // A TEACHER batch is confined to their own class AND their own section. Both resolved
        // server-side from the teacher's own Teacher row via TeacherClassScopeService, never
        // taken from the request — a class name alone stopped being an authorization boundary
        // once a class can be split into sections (Class 12 / Science vs Class 12 / Commerce
        // are different teachers' responsibilities). A legacy assignment whose section was
        // never resolved is BLOCKED here rather than silently widened to the whole class.
        String className = null;
        if (Role.TEACHER.equals(role)) {
            TeacherScope scope = teacherClassScopeService.resolveOwnScope(userId, schoolId);
            if (!scope.hasClassResponsibility()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", "You are not assigned as a class teacher, so leave decisions aren't available to you."));
            }
            if (scope.sectionRequiredButMissing()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", TeacherClassScopeService.SECTION_REQUIRED_MESSAGE));
            }
            className = scope.className();
        }

        Cookie accessTokenCookie = WebUtils.getCookie(request, "accessToken");
        if (accessTokenCookie == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Access token missing"));
        }

        Map<String, Object> userCtx = new LinkedHashMap<>();
        userCtx.put("userId", userId);
        userCtx.put("role", role);
        userCtx.put("schoolId", schoolId);
        userCtx.put("name", null);
        userCtx.put("className", className);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", decision);
        payload.put("leaveIds", leaveIds);
        payload.put("className", className);
        payload.put("user", userCtx);
        payload.put("accessToken", accessTokenCookie.getValue());

        Map<String, Object> pyResponse;
        try {
            pyResponse = callPython(HttpMethod.POST, "/workflows/leave-decisions/start", payload);
        } catch (HttpStatusCodeException e) {
            return forwardPythonError(e);
        } catch (Exception e) {
            log.error("AI leave workflow start failed for userId={}: {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI workflow service is temporarily unavailable."));
        }

        String workflowId = (String) pyResponse.get("workflowId");
        if (workflowId == null || workflowId.isBlank()) {
            log.error("AI leave workflow start: Python response missing workflowId for userId={}", userId);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "AI service returned an unexpected response."));
        }

        // Persist the batch as the graph resolved it — the graph drops any id that isn't a live,
        // in-scope PENDING request, so this row records what the human was actually shown.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resolved = (List<Map<String, Object>>) pyResponse.getOrDefault("leaveRequests", List.of());
        List<Long> resolvedIds = resolved.stream()
                .map(r -> ((Number) r.get("leaveId")).longValue())
                .collect(Collectors.toList());

        AiLeaveDecisionBatch batch = new AiLeaveDecisionBatch();
        batch.setWorkflowId(workflowId);
        batch.setSchoolId(schoolId);
        batch.setRequesterUserId(userId);
        batch.setRequesterRole(role);
        batch.setClassName(className);
        batch.setDecision(decision);
        batch.setLeaveIds(toJson(resolvedIds.isEmpty() ? leaveIds : resolvedIds));
        batch.setStatus(AiReminderBatchService.PENDING_APPROVAL);
        batchRepository.save(batch);

        return ResponseEntity.ok(pyResponse);
    }

    // ─── Approve / Reject the BATCH (not the leaves) ───────────────────────────

    @PostMapping("/{workflowId}/approve")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<?> approve(@PathVariable String workflowId, HttpServletRequest request) {
        return resume(workflowId, "approved", request);
    }

    @PostMapping("/{workflowId}/reject")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<?> reject(@PathVariable String workflowId, HttpServletRequest request) {
        return resume(workflowId, "rejected", request);
    }

    private ResponseEntity<?> resume(String workflowId, String decision, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        String userId = authService.getUserId();
        String role = authService.getRole();

        ResponseEntity<?> permissionError = requireLeaveApprovePermission(role, schoolId);
        if (permissionError != null) return permissionError;

        AiLeaveDecisionBatch batch = batchRepository.findByWorkflowId(workflowId).orElse(null);
        if (batch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown workflow."));
        }
        ResponseEntity<?> authError = checkOwnership(batch, schoolId, userId, role);
        if (authError != null) return authError;

        // Only an APPROVAL is refused; a retried rejection stays idempotent — same rule as the
        // reminder controllers.
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
        payload.put("requesterUserId", userId);
        payload.put("accessToken", accessTokenCookie.getValue());

        try {
            Map<String, Object> pyResponse = callPython(HttpMethod.POST, "/workflows/leave-decisions/" + workflowId + "/resume", payload);
            if ("rejected".equals(decision) && "rejected".equals(pyResponse.get("status"))) {
                batchService.markRejected(() -> batchRepository.findByWorkflowIdForUpdate(workflowId), batchRepository);
            }
            return ResponseEntity.ok(pyResponse);
        } catch (HttpStatusCodeException e) {
            return forwardPythonError(e);
        } catch (Exception e) {
            log.error("AI leave workflow resume failed for workflowId={}: {}", workflowId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI workflow service is temporarily unavailable."));
        }
    }

    // ─── Status ────────────────────────────────────────────────────────────────

    @GetMapping("/{workflowId}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<?> status(@PathVariable String workflowId) {
        Long schoolId = securityUtil.getSchoolId();
        String userId = authService.getUserId();
        String role = authService.getRole();

        AiLeaveDecisionBatch batch = batchRepository.findByWorkflowId(workflowId).orElse(null);
        if (batch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown workflow."));
        }
        ResponseEntity<?> authError = checkOwnership(batch, schoolId, userId, role);
        if (authError != null) return authError;

        try {
            return ResponseEntity.ok(callPython(HttpMethod.GET, "/workflows/leave-decisions/" + workflowId, null));
        } catch (HttpStatusCodeException e) {
            return forwardPythonError(e);
        } catch (Exception e) {
            log.error("AI leave workflow status fetch failed for workflowId={}: {}", workflowId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI workflow service is temporarily unavailable."));
        }
    }

    // ─── Dispatch (called only by Python's apply node, never Angular) ──────────

    @PostMapping("/{workflowId}/dispatch")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @Transactional
    public ResponseEntity<?> dispatch(@PathVariable String workflowId,
                                      @RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {

        Long schoolId = securityUtil.getSchoolId();
        String userId = authService.getUserId();
        String role = authService.getRole();

        ResponseEntity<?> permissionError = requireLeaveApprovePermission(role, schoolId);
        if (permissionError != null) return permissionError;

        AiLeaveDecisionBatch batch = batchRepository.findByWorkflowIdForUpdate(workflowId).orElse(null);
        if (batch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown workflow."));
        }
        ResponseEntity<?> authError = checkOwnership(batch, schoolId, userId, role);
        if (authError != null) return authError;

        if (AiReminderBatchService.REJECTED.equals(batch.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "This batch was rejected and cannot be applied."));
        }
        if (AiReminderBatchService.SEND_ATTEMPTED_STATUSES.contains(batch.getStatus())) {
            return ResponseEntity.ok(dispatchResponseFrom(batch));
        }

        // The ids come from the batch row, not the request body — the body is informational only,
        // so a forged dispatch cannot widen the batch beyond what the human approved.
        List<Long> leaveIds = fromJsonIds(batch.getLeaveIds());
        if (leaveIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "This batch has no leave requests to act on."));
        }

        // The section confinement, like the class one, is re-resolved from the acting teacher's
        // own Teacher row at apply time — never read back from the batch row, never from the
        // dispatch body (which Python populates and is informational only). A leave whose
        // student is not in this section is skipped by applyDecisions, so a Science
        // class-teacher can never approve or reject a Commerce student's leave even if a model
        // proposed its id and the human approved the card.
        Long effectiveSectionId = null;
        if (Role.TEACHER.equals(batch.getRequesterRole())) {
            TeacherScope scope = teacherClassScopeService.resolveOwnScope(userId, schoolId);
            if (scope.sectionRequiredButMissing()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", TeacherClassScopeService.SECTION_REQUIRED_MESSAGE));
            }
            effectiveSectionId = scope.sectionId();
        }

        LeaveStatus decision = LeaveStatus.valueOf(batch.getDecision());
        Map<Long, String> outcomes = leaveDecisionService.applyDecisions(
                leaveIds, decision, batch.getClassName(), effectiveSectionId, request);

        int applied = 0, skipped = 0, failed = 0;
        for (String outcome : outcomes.values()) {
            if (LeaveDecisionService.APPLIED.equals(outcome)) applied++;
            else if (LeaveDecisionService.isSkip(outcome)) skipped++;
            else failed++;
        }

        // A batch where nothing was applied and something failed is FAILED; one where nothing was
        // applied but everything was skipped is still SENT — refusing to overwrite other people's
        // decisions is the workflow working correctly, not an error.
        String status;
        if (failed > 0 && applied == 0) status = AiReminderBatchService.FAILED;
        else if (failed > 0) status = AiReminderBatchService.PARTIALLY_SENT;
        else status = AiReminderBatchService.SENT;

        batch.setStatus(status);
        batch.setAppliedCount(applied);
        batch.setSkippedCount(skipped);
        batch.setFailedCount(failed);
        batch.setOutcomes(toJson(capOutcomes(outcomes)));
        batch.setCompletedAt(LocalDateTime.now());
        batchRepository.save(batch);

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "APPLY_LEAVE_DECISION_AI_WORKFLOW",
                "Leave",
                null,
                null,
                "AI workflow " + workflowId + ": decision " + decision + ", " + applied + " applied, "
                        + skipped + " skipped, " + failed + " failed"
                        + (batch.getClassName() != null ? ", class " + batch.getClassName() : ", school-wide")
                        + (effectiveSectionId != null ? ", section " + effectiveSectionId : ""),
                request.getRemoteAddr()
        );

        return ResponseEntity.ok(dispatchResponseFrom(batch));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Enforces the LEAVE_APPROVE permission on top of the role check. The permission has been
     * seeded and returned at login since the permission system was introduced, but was never
     * checked anywhere server-side — so a school that removed it from a role would still have
     * seen leave approvals succeed. It is enforced here, on this new surface only, rather than
     * retrofitted onto the existing leave endpoints, which would immediately revoke access for
     * any school whose role-permission matrix has already drifted.
     */
    private ResponseEntity<?> requireLeaveApprovePermission(String role, Long schoolId) {
        try {
            List<String> keys = permissionService.getPermissionKeysForRole(role, schoolId);
            if (keys != null && !keys.contains(LEAVE_APPROVE_PERMISSION)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", "Your role does not have permission to approve or reject leave requests."));
            }
        } catch (Exception e) {
            // Never fail closed on a permission-lookup outage — the role check above already ran,
            // and that is the same level of protection every other leave endpoint relies on today.
            log.warn("Could not resolve permissions for role {}: {}", role, e.getMessage());
        }
        return null;
    }

    /** Returns a 403 if the batch isn't this caller's to act on, else null. */
    private ResponseEntity<?> checkOwnership(AiLeaveDecisionBatch batch, Long schoolId, String userId, String role) {
        if (!Objects.equals(batch.getSchoolId(), schoolId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This workflow does not belong to your school."));
        }
        // A teacher batch belongs to exactly one teacher, and only while they still hold that
        // class. An admin batch may be acted on by any admin in the school, matching how the
        // admin reminder workflows already behave.
        if (Role.TEACHER.equals(batch.getRequesterRole())) {
            if (!Objects.equals(batch.getRequesterUserId(), userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This workflow does not belong to you."));
            }
            // Re-resolved live, so a class OR section reassignment after the batch started
            // revokes the approval capability rather than leaving a stale one behind.
            TeacherScope scope = teacherClassScopeService.resolveOwnScope(userId, schoolId);
            if (!Objects.equals(batch.getClassName(), scope.className())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "You are no longer assigned to this class."));
            }
            if (scope.sectionRequiredButMissing()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", TeacherClassScopeService.SECTION_REQUIRED_MESSAGE));
            }
        } else if (Role.TEACHER.equals(role)) {
            // A teacher may never act on an admin's school-wide batch.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This workflow does not belong to you."));
        }
        return null;
    }

    private List<Long> parseLeaveIds(Object raw) {
        List<Long> ids = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) ids.add(n.longValue());
            }
        }
        return ids.stream().distinct().collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callPython(HttpMethod method, String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Secret", aiInternalSecret);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange(aiServiceUrl + path, method, entity, Map.class);
        return response.getBody();
    }

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

    private List<Map<String, Object>> capOutcomes(Map<Long, String> outcomes) {
        return outcomes.entrySet().stream()
                .limit(MAX_STORED_OUTCOMES)
                .map(e -> Map.<String, Object>of("leaveId", e.getKey(), "outcome", e.getValue()))
                .collect(Collectors.toList());
    }

    private Map<String, Object> dispatchResponseFrom(AiLeaveDecisionBatch batch) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appliedCount", batch.getAppliedCount());
        result.put("skippedCount", batch.getSkippedCount());
        result.put("failedCount", batch.getFailedCount());
        result.put("outcomes", fromJson(batch.getOutcomes()));
        return result;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize AI leave workflow field: {}", e.getMessage());
            return "[]";
        }
    }

    private List<Map<String, Object>> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize AI leave workflow field: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Long> fromJsonIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Number> raw = objectMapper.readValue(json, new TypeReference<List<Number>>() {});
            return raw.stream().map(Number::longValue).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to deserialize leave ids: {}", e.getMessage());
            return List.of();
        }
    }
}
