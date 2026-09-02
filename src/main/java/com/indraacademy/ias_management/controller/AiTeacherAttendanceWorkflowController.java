package com.indraacademy.ias_management.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ConsecutiveAbsenceDTO;
import com.indraacademy.ias_management.entity.AiTeacherAttendanceReminderBatch;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.AiTeacherAttendanceReminderBatchRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.service.AiReminderBatchService;
import com.indraacademy.ias_management.service.AttendanceReminderService;
import com.indraacademy.ias_management.service.AttendanceService;
import com.indraacademy.ias_management.service.AuditService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.TeacherClassScopeService;
import com.indraacademy.ias_management.service.TeacherClassScopeService.ScopedAccess;
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
 * AiTeacherAttendanceWorkflowController — Spring's half of the TEACHER-scoped Low-Attendance
 * Warning LangGraph workflow. Structural mirror of AiAttendanceWorkflowController (the admin,
 * school-wide variant) — same trust-boundary pattern, same tenant-isolation-against-our-own-
 * batch-row enforcement, same dispatch()-is-the-only-Postgres-write-Python-is-never-allowed-to-
 * do-itself rule. See that class's Javadoc for the full rationale, not repeated here.
 *
 * The load-bearing difference is authorization: every endpoint here additionally enforces that
 * the batch belongs to the SAME teacher who started it (not just "any admin in the school", as
 * the admin variant allows), and neither className NOR the section is ever accepted from the
 * request body — both are always resolved server-side from the teacher's own Teacher row via
 * {@link com.indraacademy.ias_management.service.TeacherClassScopeService}, both at start AND
 * re-verified on resume/dispatch, so a teacher whose class or section assignment changes after
 * starting a batch cannot still approve it for students they no longer own.
 *
 * <p>Section scoping matters here specifically because a class name stopped being an
 * authorization boundary once a class can be split into sections: without it, the class-teacher
 * of Class 12 / Science could email the parents of Class 12 / Commerce students. Selection at
 * start is already section-scoped because the graph loads the roster back through
 * AttendanceController with the teacher's own token; dispatch re-authorizes every studentId
 * individually, because those ids arrive in the dispatch body rather than from the batch row.
 */
@RestController
@RequestMapping("/api/ai/workflows/teacher-attendance-reminders")
@PreAuthorize("isAuthenticated()")
public class AiTeacherAttendanceWorkflowController {

    private static final Logger log = LoggerFactory.getLogger(AiTeacherAttendanceWorkflowController.class);
    private static final int MAX_STORED_OUTCOMES = 50;
    static final String CRITERION_BELOW_THRESHOLD = "BELOW_THRESHOLD";
    static final String CRITERION_CONSECUTIVE_ABSENCE = "CONSECUTIVE_ABSENCE";
    /** Recorded per student when a dispatched id turned out to be outside the requesting
     *  teacher's own class+section — dropped before sending, counted as neither sent nor failed. */
    static final String OUT_OF_SCOPE = "skipped_out_of_scope";

    @Value("${ai.service.url:http://localhost:8001}")
    private String aiServiceUrl;

    @Value("${ai.internal.secret}")
    private String aiInternalSecret;

    @Autowired private AuthService authService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private TeacherClassScopeService teacherClassScopeService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private AiTeacherAttendanceReminderBatchRepository batchRepository;
    @Autowired private AttendanceReminderService attendanceReminderService;
    @Autowired private AttendanceService attendanceService;
    @Autowired private AiReminderBatchService batchService;
    @Autowired private AuditService auditService;
    @Autowired private ObjectMapper objectMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    // ─── Start ─────────────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String session = (String) body.get("session");
        if (session == null || session.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "session is required"));
        }
        Object thresholdRaw = body.get("threshold");
        double threshold = thresholdRaw instanceof Number ? ((Number) thresholdRaw).doubleValue() : 75.0;

        // Which attendance pattern to select on. Validated against a closed set here rather than
        // trusted from the caller — this string reaches both the graph's loader and the audit row.
        String criterion = body.get("criterion") instanceof String s ? s.toUpperCase() : "BELOW_THRESHOLD";
        if (!CRITERION_BELOW_THRESHOLD.equals(criterion) && !CRITERION_CONSECUTIVE_ABSENCE.equals(criterion)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "criterion must be BELOW_THRESHOLD or CONSECUTIVE_ABSENCE"));
        }
        Object minDaysRaw = body.get("minConsecutiveDays");
        Integer minConsecutiveDays = minDaysRaw instanceof Number ? ((Number) minDaysRaw).intValue() : null;
        if (CRITERION_CONSECUTIVE_ABSENCE.equals(criterion)) {
            if (minConsecutiveDays == null) minConsecutiveDays = 3;
            if (minConsecutiveDays < 1 || minConsecutiveDays > 60) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "minConsecutiveDays must be between 1 and 60"));
            }
        } else {
            minConsecutiveDays = null;  // not applicable — never persist a misleading value
        }

        String userId = authService.getUserId();
        Long schoolId = securityUtil.getSchoolId();

        // className AND section are NEVER trusted from the request body — both are resolved
        // here from the teacher's own Teacher row via TeacherClassScopeService, the same
        // primitive AttendanceController/LeaveController use. A class name alone is not an
        // authorization boundary for a sectioned class (Class 12 / Science vs Class 12 /
        // Commerce), and a legacy assignment whose section was never resolved is BLOCKED
        // rather than silently treated as "the whole class".
        TeacherScope scope = teacherClassScopeService.resolveOwnScope(userId, schoolId);
        if (!scope.hasClassResponsibility()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You are not assigned as a class teacher, so attendance reminders aren't available to you."));
        }
        if (scope.sectionRequiredButMissing()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", TeacherClassScopeService.SECTION_REQUIRED_MESSAGE));
        }
        String className = scope.className();

        Cookie accessTokenCookie = WebUtils.getCookie(request, "accessToken");
        if (accessTokenCookie == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Access token missing"));
        }

        Map<String, Object> userCtx = new LinkedHashMap<>();
        userCtx.put("userId", userId);
        userCtx.put("role", "TEACHER");
        userCtx.put("schoolId", schoolId);
        userCtx.put("name", null);
        userCtx.put("className", className);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session", session);
        payload.put("className", className);
        payload.put("threshold", threshold);
        payload.put("criterion", criterion);
        payload.put("minConsecutiveDays", minConsecutiveDays);
        payload.put("user", userCtx);
        payload.put("accessToken", accessTokenCookie.getValue());

        Map<String, Object> pyResponse;
        try {
            pyResponse = callPython(HttpMethod.POST, "/workflows/teacher-attendance-reminders/start", payload);
        } catch (HttpStatusCodeException e) {
            return forwardPythonError(e);
        } catch (Exception e) {
            log.error("AI teacher attendance workflow start failed for userId={}: {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI workflow service is temporarily unavailable."));
        }

        String workflowId = (String) pyResponse.get("workflowId");
        if (workflowId == null || workflowId.isBlank()) {
            log.error("AI teacher attendance workflow start: Python response missing workflowId for userId={}", userId);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "AI service returned an unexpected response."));
        }

        // Persisted at START, not approval — the row this and every later call tenant-checks
        // against, with zero cross-service round trip, and the idempotency anchor for dispatch().
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> students = (List<Map<String, Object>>) pyResponse.getOrDefault("students", List.of());
        List<String> studentIds = students.stream().map(s -> (String) s.get("studentId")).collect(Collectors.toList());

        AiTeacherAttendanceReminderBatch batch = new AiTeacherAttendanceReminderBatch();
        batch.setWorkflowId(workflowId);
        batch.setSchoolId(schoolId);
        batch.setTeacherUserId(userId);
        batch.setSession(session);
        batch.setClassName(className);
        batch.setThreshold(threshold);
        batch.setCriterion(criterion);
        batch.setMinConsecutiveDays(minConsecutiveDays);
        batch.setStatus(AiReminderBatchService.PENDING_APPROVAL);
        batch.setStudentIds(toJson(studentIds));
        batchRepository.save(batch);

        return ResponseEntity.ok(pyResponse);
    }

    // ─── Approve / Reject ─────────────────────────────────────────────────────

    @PostMapping("/{workflowId}/approve")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<?> approve(@PathVariable String workflowId, HttpServletRequest request) {
        return resume(workflowId, "approved", request);
    }

    @PostMapping("/{workflowId}/reject")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<?> reject(@PathVariable String workflowId, HttpServletRequest request) {
        return resume(workflowId, "rejected", request);
    }

    private ResponseEntity<?> resume(String workflowId, String decision, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        String userId = authService.getUserId();

        AiTeacherAttendanceReminderBatch batch = batchRepository.findByWorkflowId(workflowId).orElse(null);
        if (batch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown workflow."));
        }
        ResponseEntity<?> authError = checkOwnership(batch, schoolId, userId);
        if (authError != null) {
            return authError;
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
        payload.put("teacherUserId", userId);
        payload.put("accessToken", accessTokenCookie.getValue());

        try {
            Map<String, Object> pyResponse = callPython(HttpMethod.POST, "/workflows/teacher-attendance-reminders/" + workflowId + "/resume", payload);
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
            log.error("AI teacher attendance workflow resume failed for workflowId={}: {}", workflowId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI workflow service is temporarily unavailable."));
        }
    }

    // ─── Status ────────────────────────────────────────────────────────────────

    @GetMapping("/{workflowId}")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<?> status(@PathVariable String workflowId) {
        Long schoolId = securityUtil.getSchoolId();
        String userId = authService.getUserId();

        AiTeacherAttendanceReminderBatch batch = batchRepository.findByWorkflowId(workflowId).orElse(null);
        if (batch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown workflow."));
        }
        ResponseEntity<?> authError = checkOwnership(batch, schoolId, userId);
        if (authError != null) {
            return authError;
        }

        try {
            Map<String, Object> pyResponse = callPython(HttpMethod.GET, "/workflows/teacher-attendance-reminders/" + workflowId, null);
            return ResponseEntity.ok(pyResponse);
        } catch (HttpStatusCodeException e) {
            return forwardPythonError(e);
        } catch (Exception e) {
            log.error("AI teacher attendance workflow status fetch failed for workflowId={}: {}", workflowId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "AI workflow service is temporarily unavailable."));
        }
    }

    // ─── Dispatch (called only by Python's send_reminders node, never Angular) ────

    @PostMapping("/{workflowId}/dispatch")
    @PreAuthorize("hasRole('TEACHER')")
    @Transactional
    public ResponseEntity<?> dispatch(
            @PathVariable String workflowId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        Long schoolId = securityUtil.getSchoolId();
        String userId = authService.getUserId();

        AiTeacherAttendanceReminderBatch batch = batchRepository.findByWorkflowIdForUpdate(workflowId).orElse(null);
        if (batch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown workflow."));
        }
        ResponseEntity<?> authError = checkOwnership(batch, schoolId, userId);
        if (authError != null) {
            return authError;
        }

        // A rejected batch must never send. Refused rather than replayed: unlike an
        // already-sent batch there is no stored outcome to return, and answering "0 sent, 0
        // failed" would read as a successful no-op send instead of a refusal.
        if (AiReminderBatchService.REJECTED.equals(batch.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "This batch was rejected and cannot be dispatched."));
        }

        // Idempotent short-circuit — the row-lock above serializes concurrent dispatch
        // calls for the same workflowId, so this check is race-free: belt-and-suspenders
        // alongside Python's own resume-lock, in case that ever has a bug or is bypassed.
        if (AiReminderBatchService.SEND_ATTEMPTED_STATUSES.contains(batch.getStatus())) {
            return ResponseEntity.ok(dispatchResponseFrom(batch));
        }

        @SuppressWarnings("unchecked")
        List<String> studentIds = (List<String>) body.get("studentIds");
        String session = (String) body.get("session");
        if (studentIds == null || studentIds.isEmpty() || session == null || session.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "studentIds and session are required"));
        }

        // The studentIds arrive in the dispatch body, i.e. they originate from the graph — so
        // each one is re-authorized here against the teacher's OWN class+section, resolved live
        // from their Teacher row. A class-level match alone is not enough: the roster the graph
        // selected from is class-named, and a sectioned class (Class 12 / Science vs Class 12 /
        // Commerce) belongs to two different teachers. This is the same per-student check
        // MarkController.getStudentMarksForExam and ReportCardController.checkTeacherOwnsStudents
        // apply. Anything out of scope is dropped and recorded, never emailed.
        TeacherScope scope = teacherClassScopeService.resolveOwnScope(userId, schoolId);
        if (scope.sectionRequiredButMissing()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", TeacherClassScopeService.SECTION_REQUIRED_MESSAGE));
        }
        Long effectiveSectionId = scope.sectionId();

        List<String> scopedStudentIds = new ArrayList<>();
        Map<String, String> outOfScope = new LinkedHashMap<>();
        for (String studentId : studentIds) {
            Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId).orElse(null);
            String studentClassName = student != null ? student.getClassName() : null;
            Long studentSectionId = student != null ? student.getSectionId() : null;
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToStudent(
                    Role.TEACHER, userId, schoolId, studentClassName, studentSectionId);
            if (access.allowed()) {
                scopedStudentIds.add(studentId);
            } else {
                log.warn("AI teacher attendance workflow {}: student {} is outside the requesting teacher's "
                        + "class/section scope — dropped from dispatch.", workflowId, studentId);
                outOfScope.put(studentId, OUT_OF_SCOPE);
            }
        }
        if (scopedStudentIds.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "None of these students are in your assigned class and section."));
        }

        // For a consecutive-absence batch the email states the actual streak dates, so they are
        // recomputed HERE from the batch's own stored className/minConsecutiveDays rather than
        // accepted from the dispatch body — Spring stays the sole authority on what a parent is
        // told, exactly as it already is for the attendance percentage. Scoped to the teacher's
        // own section too, so the recomputation can never read another section's attendance.
        Map<String, List<String>> recentAbsenceDates = Map.of();
        if (CRITERION_CONSECUTIVE_ABSENCE.equals(batch.getCriterion()) && batch.getMinConsecutiveDays() != null) {
            recentAbsenceDates = attendanceService
                    .getConsecutiveAbsentees(batch.getClassName(), batch.getMinConsecutiveDays(), null, session, effectiveSectionId)
                    .stream()
                    .collect(Collectors.toMap(ConsecutiveAbsenceDTO::getStudentId, ConsecutiveAbsenceDTO::getAbsentDates));
        }

        Map<String, String> sendOutcomes = attendanceReminderService
                .sendAttendanceReminderEmailsWithOutcomes(scopedStudentIds, session, recentAbsenceDates, workflowId);
        int sent = 0, failed = 0;
        for (String outcome : sendOutcomes.values()) {
            if ("sent".equals(outcome)) sent++; else failed++;
        }

        // Dropped students are recorded alongside the real send results so the stored batch is
        // an honest account of what happened, but they count as neither sent nor failed — a
        // refusal to email outside the teacher's section is the check working, not an error.
        Map<String, String> outcomes = new LinkedHashMap<>(sendOutcomes);
        outcomes.putAll(outOfScope);

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
                "SEND_ATTENDANCE_REMINDER_AI_WORKFLOW_TEACHER",
                "Attendance",
                null,
                null,
                "AI workflow " + workflowId + ": " + sent + " sent, " + failed + " failed, "
                        + outOfScope.size() + " dropped out-of-scope, class "
                        + batch.getClassName()
                        + (effectiveSectionId != null ? " (section " + effectiveSectionId + ")" : "")
                        + ", session " + session + ", criterion " + batch.getCriterion()
                        + (batch.getMinConsecutiveDays() != null ? " (>=" + batch.getMinConsecutiveDays() + " consecutive days)" : ""),
                request.getRemoteAddr()
        );

        return ResponseEntity.ok(dispatchResponseFrom(batch));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Returns a 403/404 ResponseEntity if the batch isn't this teacher's own, else null. */
    private ResponseEntity<?> checkOwnership(AiTeacherAttendanceReminderBatch batch, Long schoolId, String userId) {
        if (!Objects.equals(batch.getSchoolId(), schoolId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This workflow does not belong to your school."));
        }
        // Unlike the admin variant (any admin in the school may approve), a teacher-scoped
        // batch belongs to exactly one teacher.
        if (!Objects.equals(batch.getTeacherUserId(), userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This workflow does not belong to you."));
        }
        // Re-verify current class AND section ownership, not just at start — a teacher's class
        // or section reassignment after the batch started must not leave a stale approval
        // capability, and a class whose section assignment is missing/ambiguous is blocked
        // outright rather than falling back to the combined class.
        TeacherScope scope = teacherClassScopeService.resolveOwnScope(userId, schoolId);
        if (!Objects.equals(batch.getClassName(), scope.className())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "You are no longer assigned to this class."));
        }
        if (scope.sectionRequiredButMissing()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", TeacherClassScopeService.SECTION_REQUIRED_MESSAGE));
        }
        return null;
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

    private Map<String, Object> dispatchResponseFrom(AiTeacherAttendanceReminderBatch batch) {
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
            log.warn("Failed to serialize AI teacher attendance workflow field: {}", e.getMessage());
            return "[]";
        }
    }

    private List<Map<String, Object>> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize AI teacher attendance workflow field: {}", e.getMessage());
            return List.of();
        }
    }
}
