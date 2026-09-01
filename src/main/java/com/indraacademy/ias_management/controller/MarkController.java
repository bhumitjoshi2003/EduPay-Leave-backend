package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.*;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.service.ExamConfigService;
import com.indraacademy.ias_management.service.MarkService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.service.TeacherClassScopeService;
import com.indraacademy.ias_management.service.TeacherClassScopeService.ScopedAccess;
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
import java.util.Map;

@RestController
@RequestMapping("/api/marks")
public class MarkController {

    private static final Logger log = LoggerFactory.getLogger(MarkController.class);

    @Autowired private MarkService markService;
    @Autowired private ExamConfigService examConfigService;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ParentPortalService parentPortalService;
    @Autowired private TeacherClassScopeService teacherClassScopeService;

    // ─── Mark Entry Mode A: by subject ───────────────────────────────────────

    /**
     * Returns all students who should sit a given exam subject, with their current mark.
     * TEACHER: only accessible if the exam's className matches their classTeacher field.
     * ADMIN: full access.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @GetMapping("/exam/{examSubjectEntryId}/students")
    public ResponseEntity<?> getStudentsForSubjectEntry(
            @PathVariable Long examSubjectEntryId,
            @RequestParam(required = false) Long sectionId) {
        log.info("GET /api/marks/exam/{}/students sectionId={}", examSubjectEntryId, sectionId);
        String className = examConfigService.resolveClassName(examSubjectEntryId).orElse(null);
        ScopedAccess access = checkTeacherClassAccess(className);
        if (!access.allowed()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
        }
        if (Role.TEACHER.equals(securityUtil.getRole())) {
            sectionId = access.effectiveSectionId();
        }

        List<StudentSubjectMarkDTO> result = markService.getStudentsForSubjectEntry(examSubjectEntryId, sectionId);
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
        // Two-part check: the exam's class must be the teacher's own (checkTeacherClassAccess),
        // AND — since a class-level match alone doesn't prove this SPECIFIC student is in the
        // teacher's own section — the student's own section must also match.
        if (Role.TEACHER.equals(securityUtil.getRole())) {
            Long schoolId = securityUtil.getSchoolId();
            Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId).orElse(null);
            String studentClass = student != null ? student.getClassName() : null;
            Long studentSectionId = student != null ? student.getSectionId() : null;
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToStudent(
                    securityUtil.getRole(), securityUtil.getUsername(), schoolId, studentClass, studentSectionId);
            if (!access.allowed()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
            }
        }

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
        // For TEACHER, verify ALL entries belong to the teacher's own class AND section — a
        // class-level match alone isn't enough, since the exam's class can be shared across
        // sections; each entry's actual studentId must also be in the teacher's own section.
        if (Role.TEACHER.equals(securityUtil.getRole())) {
            Long schoolId = securityUtil.getSchoolId();
            for (MarkEntryRequest req : requests) {
                if (req.getExamSubjectEntryId() == null) continue;
                String className = examConfigService
                        .resolveClassName(req.getExamSubjectEntryId()).orElse(null);
                ScopedAccess classCheck = checkTeacherClassAccess(className);
                if (!classCheck.allowed()) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(classCheck.errorMessage());
                }
                Student student = studentRepository.findByStudentIdAndSchoolId(req.getStudentId(), schoolId).orElse(null);
                String studentClass = student != null ? student.getClassName() : null;
                Long studentSectionId = student != null ? student.getSectionId() : null;
                ScopedAccess studentCheck = teacherClassScopeService.authorizeAndScopeToStudent(
                        securityUtil.getRole(), securityUtil.getUsername(), schoolId, studentClass, studentSectionId);
                if (!studentCheck.allowed()) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(studentCheck.errorMessage());
                }
            }
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
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "', '" + Role.STUDENT + "', '" + Role.PARENT + "')")
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
        if (Role.PARENT.equals(callerRole)) {
            parentPortalService.assertChildAccess(studentId, ParentPortalService.ChildPermission.RESULTS);
        }
        // TEACHER: this endpoint previously had NO class/section check at all — any teacher
        // could view any student's results school-wide. Scoped to the teacher's own class+section.
        if (Role.TEACHER.equals(callerRole)) {
            Long schoolId = securityUtil.getSchoolId();
            Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId).orElse(null);
            String studentClass = student != null ? student.getClassName() : null;
            Long studentSectionId = student != null ? student.getSectionId() : null;
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToStudent(
                    callerRole, callerUserId, schoolId, studentClass, studentSectionId);
            if (!access.allowed()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
            }
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
            @PathVariable Long examConfigId,
            @RequestParam(required = false) Long sectionId) {
        log.info("GET /api/marks/class/{}/exam/{} sectionId={}", className, examConfigId, sectionId);
        ScopedAccess access = checkTeacherClassAccess(className);
        if (!access.allowed()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
        }
        if (Role.TEACHER.equals(securityUtil.getRole())) {
            sectionId = access.effectiveSectionId();
        }

        List<ClassStudentResultDTO> results = markService.getClassResults(className, examConfigId, sectionId);
        return ResponseEntity.ok(results);
    }

    // ─── Consolidated exam performance ─────────────────────────────────────────

    /**
     * One class's exam, fully aggregated: class average, ranked student list
     * (top/lowest scorer are just the first/last entries), and subject averages.
     * TEACHER: only accessible for their own class. ADMIN: full access.
     * Replaces the old client-side "fetch exam list, pick one, fetch results,
     * aggregate" round trip with a single call.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @GetMapping("/class/{className}/exam-performance")
    public ResponseEntity<?> getClassExamPerformance(
            @PathVariable String className,
            @RequestParam String session,
            @RequestParam(required = false) String examName) {
        log.info("GET /api/marks/class/{}/exam-performance?session={}&examName={}", className, session, examName);
        ScopedAccess access = checkTeacherClassAccess(className);
        if (!access.allowed()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
        }
        Long sectionId = Role.TEACHER.equals(securityUtil.getRole()) ? access.effectiveSectionId() : null;

        List<com.indraacademy.ias_management.entity.ExamConfig> exams = markService.getExamsForClass(session, className);
        if (exams.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "className", className, "session", session, "noExamsYet", true,
                    "message", "No exams are configured for " + className + " in session " + session + " yet."));
        }

        var chosen = markService.resolveExam(exams, examName);
        if (chosen.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "error", "No exam named '" + examName + "' found for " + className + " in " + session + ".",
                    "availableExams", exams.stream().map(com.indraacademy.ias_management.entity.ExamConfig::getExamName).toList()));
        }

        return ResponseEntity.ok(markService.computeClassExamPerformance(className, chosen.get(), sectionId));
    }

    /**
     * Every active class's own latest exam performance, in one call — ADMIN only.
     * See MarkService.getSchoolPerformanceSummary for why "no exam configured" and
     * "exam configured but no marks entered" are kept as separate lists.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @GetMapping("/school/performance-summary")
    public ResponseEntity<?> getSchoolPerformanceSummary(@RequestParam String session) {
        log.info("GET /api/marks/school/performance-summary?session={}", session);
        return ResponseEntity.ok(markService.getSchoolPerformanceSummary(session));
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    /**
     * For TEACHER callers: verifies their classTeacher field (and, when their class has
     * sections, classTeacherSectionId) matches/covers the given className. ADMIN callers are
     * always allowed and unrestricted. The returned ScopedAccess's effectiveSectionId — never
     * any client-supplied sectionId — is what callers must use to filter/scope their actual
     * data access.
     */
    private ScopedAccess checkTeacherClassAccess(String className) {
        ScopedAccess access = teacherClassScopeService.authorizeAndScopeToClass(
                securityUtil.getRole(), securityUtil.getUsername(), securityUtil.getSchoolId(), className, null);
        if (!access.allowed()) {
            log.warn("Teacher {} attempted to access marks for class {}: {}",
                    securityUtil.getUsername(), className, access.errorMessage());
        }
        return access;
    }
}
