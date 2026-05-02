package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.*;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.service.ExamConfigService;
import com.indraacademy.ias_management.service.MarkService;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/marks")
@CrossOrigin(origins = "http://localhost:4200")
public class MarkController {

    private static final Logger log = LoggerFactory.getLogger(MarkController.class);

    @Autowired private MarkService markService;
    @Autowired private ExamConfigService examConfigService;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private SecurityUtil securityUtil;

    // ─── Mark Entry Mode A: by subject ───────────────────────────────────────

    /**
     * Returns all students who should sit a given exam subject, with their current mark.
     * TEACHER: only accessible if the exam's className matches their classTeacher field.
     * ADMIN: full access.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @GetMapping("/exam/{examSubjectEntryId}/students")
    public ResponseEntity<?> getStudentsForSubjectEntry(
            @PathVariable Long examSubjectEntryId) {
        log.info("GET /api/marks/exam/{}/students", examSubjectEntryId);
        String className = examConfigService.resolveClassName(examSubjectEntryId).orElse(null);
        ResponseEntity<?> authCheck = checkTeacherClassAccess(className);
        if (authCheck != null) return authCheck;

        List<StudentSubjectMarkDTO> result = markService.getStudentsForSubjectEntry(examSubjectEntryId);
        return ResponseEntity.ok(result);
    }

    // ─── Mark Entry Mode B: by student ───────────────────────────────────────

    /**
     * Returns all subject entries in an exam with the given student's current marks.
     * TEACHER: only accessible if the exam's className matches their classTeacher.
     * ADMIN: full access.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @GetMapping("/student/{studentId}/exam/{examConfigId}")
    public ResponseEntity<?> getStudentMarksForExam(
            @PathVariable String studentId,
            @PathVariable Long examConfigId) {
        log.info("GET /api/marks/student/{}/exam/{}", studentId, examConfigId);
        String className = examConfigService.resolveClassNameForExam(examConfigId).orElse(null);
        ResponseEntity<?> authCheck = checkTeacherClassAccess(className);
        if (authCheck != null) return authCheck;

        List<StudentExamSubjectDTO> result = markService.getStudentMarksForExam(studentId, examConfigId);
        return ResponseEntity.ok(result);
    }

    // ─── Bulk mark save ───────────────────────────────────────────────────────

    /**
     * Upserts marks for multiple students. Each entry is saved independently.
     * TEACHER: validated to only save marks for their own class (first entry's className is used).
     * ADMIN: full access.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @PostMapping("/bulk")
    public ResponseEntity<?> bulkSaveMarks(@RequestBody List<MarkEntryRequest> requests,
                                           HttpServletRequest request) {
        log.info("POST /api/marks/bulk: {} entries", requests != null ? requests.size() : 0);
        if (requests == null || requests.isEmpty()) {
            return ResponseEntity.badRequest().body("Request body must be a non-empty list.");
        }
        // For TEACHER, verify class access using the first entry's exam subject
        if (Role.TEACHER.equals(securityUtil.getRole()) && requests.get(0).getExamSubjectEntryId() != null) {
            String className = examConfigService
                    .resolveClassName(requests.get(0).getExamSubjectEntryId()).orElse(null);
            ResponseEntity<?> authCheck = checkTeacherClassAccess(className);
            if (authCheck != null) return authCheck;
        }

        MarkBulkResultDTO result = markService.bulkSaveMarks(requests, request);
        return ResponseEntity.ok(result);
    }

    // ─── Student results view ─────────────────────────────────────────────────

    /**
     * Full exam results for a student in a session (grouped by exam).
     * STUDENT: can only view their own results.
     * TEACHER + ADMIN: can view any student.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "', '" + Role.STUDENT + "')")
    @GetMapping("/student/{studentId}/results")
    public ResponseEntity<?> getStudentResults(
            @PathVariable String studentId,
            @RequestParam(required = false) String session) {
        log.info("GET /api/marks/student/{}/results?session={}", studentId, session);

        // Students may only view their own results
        String callerRole   = securityUtil.getRole();
        String callerUserId = securityUtil.getUsername();
        if (Role.STUDENT.equals(callerRole) && !callerUserId.equals(studentId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Students can only view their own results.");
        }

        List<ExamResultDTO> results = markService.getStudentResults(studentId, session);
        return ResponseEntity.ok(results);
    }

    // ─── Class-wide results ───────────────────────────────────────────────────

    /**
     * Full class results for an exam — list of all students with all subject marks.
     * TEACHER: only accessible for their own class.
     * ADMIN: full access.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @GetMapping("/class/{className}/exam/{examConfigId}")
    public ResponseEntity<?> getClassResults(
            @PathVariable String className,
            @PathVariable Long examConfigId) {
        log.info("GET /api/marks/class/{}/exam/{}", className, examConfigId);
        ResponseEntity<?> authCheck = checkTeacherClassAccess(className);
        if (authCheck != null) return authCheck;

        List<ClassStudentResultDTO> results = markService.getClassResults(className, examConfigId);
        return ResponseEntity.ok(results);
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    /**
     * For TEACHER callers: verifies their classTeacher field matches the given className.
     * Returns a 403 ResponseEntity if access is denied, or null if access is allowed.
     * ADMIN callers always get null (access allowed).
     */
    private ResponseEntity<?> checkTeacherClassAccess(String className) {
        if (!Role.TEACHER.equals(securityUtil.getRole())) return null;

        String teacherId = securityUtil.getUsername();
        String teacherClass = teacherRepository.findById(teacherId)
                .map(t -> t.getClassTeacher())
                .orElse(null);

        if (teacherClass == null || !teacherClass.equals(className)) {
            log.warn("Teacher {} attempted to access marks for class {} (their class: {})",
                    teacherId, className, teacherClass);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Teachers can only access marks for their own class.");
        }
        return null;
    }
}
