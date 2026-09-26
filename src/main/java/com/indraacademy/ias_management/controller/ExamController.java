package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.ExamConfig;
import com.indraacademy.ias_management.entity.ExamSubjectEntry;
import com.indraacademy.ias_management.service.ExamConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/exams")
public class ExamController {

    private static final Logger log = LoggerFactory.getLogger(ExamController.class);

    @Autowired private ExamConfigService examConfigService;
    @Autowired private com.indraacademy.ias_management.util.SecurityUtil securityUtil;
    @Autowired private com.indraacademy.ias_management.service.TeacherClassScopeService teacherClassScopeService;

    // ─── ExamConfig ───────────────────────────────────────────────────────────

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @GetMapping
    public ResponseEntity<?> getExams(
            @RequestParam(required = false) String session,
            @RequestParam(required = false) String className) {
        log.info("GET /api/exams?session={}&className={}", session, className);
        if (Role.TEACHER.equals(securityUtil.getRole())) {
            // A teacher only sees the exams of the class they are class teacher of (ADMIN stays
            // school-wide). Exams are class-level; a section assignment only has to be valid.
            Long schoolId = securityUtil.getSchoolId();
            String teacherId = securityUtil.getUsername();
            String requested = className;
            if (requested == null || requested.isBlank()) {
                requested = teacherClassScopeService.resolveOwnScope(teacherId, schoolId).className();
                if (requested == null || requested.isBlank()) return ResponseEntity.ok(List.of());
            }
            var access = teacherClassScopeService.authorizeAndScopeToClass(Role.TEACHER, teacherId, schoolId, requested, null);
            if (!access.allowed()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", access.errorMessage()));
            }
            className = requested;
        }
        return ResponseEntity.ok(examConfigService.getExams(session, className));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<?> addExam(@RequestBody Map<String, String> body) {
        String session   = body.get("session");
        String className = body.get("className");
        String examName  = body.get("examName");
        log.info("POST /api/exams: session={}, className={}, examName={}", session, className, examName);
        ExamConfig saved = examConfigService.addExam(session, className, examName);
        return new ResponseEntity<>(saved, HttpStatus.CREATED);
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteExam(@PathVariable Long id) {
        log.info("DELETE /api/exams/{}", id);
        examConfigService.deleteExam(id);
        return ResponseEntity.noContent().build();
    }

    // ─── Result publishing (ADMIN only; teachers never publish) ──────────────

    /** Makes the exam's results visible to students and parents and locks its marks. */
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/{id}/publish")
    public ResponseEntity<ExamConfig> publishResults(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        log.info("POST /api/exams/{}/publish", id);
        return ResponseEntity.ok(examConfigService.publishResults(id, request.getRemoteAddr()));
    }

    /** Hides the exam's results from students and parents again (marks become editable). */
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/{id}/unpublish")
    public ResponseEntity<ExamConfig> unpublishResults(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        log.info("POST /api/exams/{}/unpublish", id);
        return ResponseEntity.ok(examConfigService.unpublishResults(id, request.getRemoteAddr()));
    }

    // ─── ExamSubjectEntry ─────────────────────────────────────────────────────

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @GetMapping("/{examId}/subjects")
    public ResponseEntity<?> getExamSubjects(@PathVariable Long examId) {
        log.info("GET /api/exams/{}/subjects", examId);
        try {
            return ResponseEntity.ok(examConfigService.getExamSubjects(examId));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/{examId}/subjects")
    public ResponseEntity<?> addExamSubject(@PathVariable Long examId,
                                            @RequestBody Map<String, Object> body) {
        String subjectName = (String) body.get("subjectName");
        Integer maxMarks   = body.get("maxMarks") != null
                ? Integer.valueOf(body.get("maxMarks").toString()) : null;
        LocalDate examDate = body.get("examDate") != null
                ? LocalDate.parse(body.get("examDate").toString()) : null;
        log.info("POST /api/exams/{}/subjects: subject={}, maxMarks={}", examId, subjectName, maxMarks);
        try {
            ExamSubjectEntry saved = examConfigService.addExamSubject(examId, subjectName, maxMarks, examDate);
            return new ResponseEntity<>(saved, HttpStatus.CREATED);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PutMapping("/{examId}/subjects/bulk")
    public ResponseEntity<?> bulkSyncExamSubjects(@PathVariable Long examId,
                                                   @RequestBody List<Map<String, Object>> body) {
        log.info("PUT /api/exams/{}/subjects/bulk: {} subjects", examId, body.size());
        try {
            List<ExamConfigService.BulkSubjectRequest> requests = new java.util.ArrayList<>();
            for (Map<String, Object> item : body) {
                ExamConfigService.BulkSubjectRequest req = new ExamConfigService.BulkSubjectRequest();
                req.subjectName = (String) item.get("subjectName");
                req.maxMarks = item.get("maxMarks") != null
                        ? Integer.valueOf(item.get("maxMarks").toString()) : null;
                req.examDate = item.get("examDate") != null && !item.get("examDate").toString().isEmpty()
                        ? LocalDate.parse(item.get("examDate").toString()) : null;
                requests.add(req);
            }
            List<ExamSubjectEntry> result = examConfigService.bulkSyncExamSubjects(examId, requests);
            return ResponseEntity.ok(result);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PutMapping("/subjects/{entryId}")
    public ResponseEntity<?> updateExamSubject(@PathVariable Long entryId,
                                               @RequestBody Map<String, Object> body) {
        Integer maxMarks = body.get("maxMarks") != null
                ? Integer.valueOf(body.get("maxMarks").toString()) : null;
        LocalDate examDate = body.get("examDate") != null
                ? LocalDate.parse(body.get("examDate").toString()) : null;
        log.info("PUT /api/exams/subjects/{}: maxMarks={}, examDate={}", entryId, maxMarks, examDate);
        try {
            ExamSubjectEntry updated = examConfigService.updateExamSubject(entryId, maxMarks, examDate);
            return ResponseEntity.ok(updated);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @DeleteMapping("/subjects/{entryId}")
    public ResponseEntity<?> deleteExamSubject(@PathVariable Long entryId) {
        log.info("DELETE /api/exams/subjects/{}", entryId);
        try {
            examConfigService.deleteExamSubject(entryId);
            return ResponseEntity.noContent().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }
}
