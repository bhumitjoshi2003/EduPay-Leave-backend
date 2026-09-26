package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.AttendanceSheetDtos.SheetView;
import com.indraacademy.ias_management.dto.AttendanceSheetDtos.SubmitRequest;
import com.indraacademy.ias_management.dto.AttendanceInsightsDtos;
import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ClassAttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ConsecutiveAbsenceDTO;
import com.indraacademy.ias_management.dto.DailyAttendanceDTO;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.service.AbsenceChargeService;
import com.indraacademy.ias_management.service.AttendanceInsightsService;
import com.indraacademy.ias_management.service.AttendanceService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.service.TeacherClassScopeService;
import com.indraacademy.ias_management.service.TeacherClassScopeService.ScopedAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Student Attendance V2. Marking goes through the /sheet endpoints (roster, full submission,
 * removal), where AttendanceService decides the school, current session, roster and the
 * TEACHER/ADMIN scope. The read endpoints keep their paths and field names.
 */
@RestController
@RequestMapping("/api/attendance")
@PreAuthorize("isAuthenticated()")
public class AttendanceController {

    private static final Logger log = LoggerFactory.getLogger(AttendanceController.class);

    @Autowired private AttendanceService attendanceService;
    @Autowired private AbsenceChargeService absenceChargeService;
    @Autowired private AttendanceInsightsService attendanceInsightsService;
    @Autowired private AuthService authService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private com.indraacademy.ias_management.util.SecurityUtil securityUtil;
    @Autowired private ParentPortalService parentPortalService;
    @Autowired private TeacherClassScopeService teacherClassScopeService;

    // ─── Marking ─────────────────────────────────────────────────────────

    /** The roster for a class (section) day — date defaults to the school's today. */
    @PreAuthorize("hasAnyRole('" + Role.TEACHER + "', '" + Role.ADMIN + "')")
    @GetMapping("/sheet")
    public SheetView getSheet(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                              @RequestParam(required = false) Long classId,
                              @RequestParam(required = false) Long sectionId) {
        return attendanceService.getSheet(date, classId, sectionId);
    }

    /** Saves a full submission: an explicit PRESENT/ABSENT for every rostered student. */
    @PreAuthorize("hasAnyRole('" + Role.TEACHER + "', '" + Role.ADMIN + "')")
    @PutMapping("/sheet")
    public SheetView submit(@RequestBody SubmitRequest submitRequest, HttpServletRequest request) {
        return attendanceService.submit(submitRequest, request.getRemoteAddr());
    }

    @PreAuthorize("hasAnyRole('" + Role.TEACHER + "', '" + Role.ADMIN + "')")
    @DeleteMapping("/sheet")
    public ResponseEntity<Void> deleteSubmission(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                 @RequestParam(required = false) Long classId,
                                                 @RequestParam(required = false) Long sectionId,
                                                 HttpServletRequest request) {
        attendanceService.deleteSubmission(date, classId, sectionId, request.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasAnyRole('" + Role.TEACHER + "', '" + Role.ADMIN + "')")
    @GetMapping("/calendar-config")
    public ResponseEntity<?> getAttendanceCalendarConfig() {
        Long schoolId = securityUtil.getSchoolId();
        return schoolRepository.findById(schoolId)
                .<ResponseEntity<?>>map(s -> {
                    if (s.getWorkingDays() == null || s.getWorkingDays().isBlank()) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                                "message", "School working days are not configured. Please update School Settings."));
                    }
                    return ResponseEntity.ok(Map.of(
                            "workingDays", s.getWorkingDays(),
                            "timezone", s.getTimezone() != null ? s.getTimezone() : "Asia/Kolkata"));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ─── Fee: absences without approved leave (see AbsenceChargeService) ──

    /** Chargeable absences for the fee screens — same path and plain-number response as before. */
    @GetMapping("/unapplied-leave-count/{studentId}/session/{session}")
    public ResponseEntity<?> getTotalUnappliedLeaveCount(@PathVariable String studentId, @PathVariable String session) {
        ResponseEntity<?> deniedResponse = checkStudentDataAccess(studentId);
        if (deniedResponse != null) return deniedResponse;
        return ResponseEntity.ok(absenceChargeService.countChargeable(studentId, session));
    }

    // ─── Summaries ───────────────────────────────────────────────────────

    /** GET /api/attendance/summary/student/{studentId}/daily?month=4&year=2026 */
    @GetMapping("/summary/student/{studentId}/daily")
    public ResponseEntity<?> getDailyAttendance(@PathVariable String studentId, @RequestParam int month, @RequestParam int year) {
        ResponseEntity<?> deniedResponse = checkStudentDataAccess(studentId);
        if (deniedResponse != null) return deniedResponse;
        ResponseEntity<?> rangeError = validateMonthAndYear(month, year);
        if (rangeError != null) return rangeError;
        DailyAttendanceDTO result = attendanceService.getDailyAttendance(studentId, month, year);
        return ResponseEntity.ok(result);
    }

    /** GET /api/attendance/summary/student/{studentId}?type=month&month=4&year=2026 | ?type=year&session=2025-2026 */
    @GetMapping("/summary/student/{studentId}")
    public ResponseEntity<?> getStudentAttendanceSummary(@PathVariable String studentId, @RequestParam String type,
                                                         @RequestParam(required = false) Integer month,
                                                         @RequestParam(required = false) Integer year,
                                                         @RequestParam(required = false) String session) {
        ResponseEntity<?> deniedResponse = checkStudentDataAccess(studentId);
        if (deniedResponse != null) return deniedResponse;
        if (month != null || year != null) {
            ResponseEntity<?> rangeError = validateMonthAndYear(month, year);
            if (rangeError != null) return rangeError;
        }
        AttendanceSummaryDTO summary = attendanceService.getStudentSummary(studentId, type, month, year, session);
        return ResponseEntity.ok(summary);
    }

    /** GET /api/attendance/summary/class/{className}?type=month&month=4&year=2026 | ?type=year&session=2025-2026 */
    @GetMapping("/summary/class/{className}")
    public ResponseEntity<?> getClassAttendanceSummary(@PathVariable String className,
                                                       @RequestParam(defaultValue = "month") String type,
                                                       @RequestParam(required = false) Integer month,
                                                       @RequestParam(required = false) Integer year,
                                                       @RequestParam(required = false) String session,
                                                       @RequestParam(required = false) Long sectionId) {
        String currentRole = authService.getRole();
        if (Role.STUDENT.equals(currentRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Students cannot access class attendance summaries.");
        }
        // Parents must use /summary/student/{studentId}, which enforces assertChildAccess.
        if (Role.PARENT.equals(currentRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Parents cannot access class attendance summaries.");
        }
        // TEACHER: only their assigned class/section — the effective section is always their own.
        if (Role.TEACHER.equals(currentRole)) {
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToClass(
                    currentRole, authService.getUserId(), securityUtil.getSchoolId(), className, sectionId);
            if (!access.allowed()) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
            sectionId = access.effectiveSectionId();
        }
        if (month != null || year != null) {
            ResponseEntity<?> rangeError = validateMonthAndYear(month, year);
            if (rangeError != null) return rangeError;
        }
        List<ClassAttendanceSummaryDTO> summary = attendanceService.getClassSummary(className, type, month, year, session, sectionId);
        return ResponseEntity.ok(summary);
    }

    /**
     * GET /api/attendance/consecutive-absentees/class/{className}?minDays=3&session=2025-2026&lookbackDays=60
     * Students ABSENT on each of the class's (section's) last {minDays} submitted days.
     */
    @GetMapping("/consecutive-absentees/class/{className}")
    public ResponseEntity<?> getConsecutiveAbsentees(@PathVariable String className,
                                                     @RequestParam(defaultValue = "3") int minDays,
                                                     @RequestParam(required = false) String session,
                                                     @RequestParam(required = false) Integer lookbackDays) {
        String currentRole = authService.getRole();
        if (Role.STUDENT.equals(currentRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Students cannot access class attendance summaries.");
        }
        if (Role.PARENT.equals(currentRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Parents cannot access class attendance summaries.");
        }
        Long effectiveSectionId = null;
        if (Role.TEACHER.equals(currentRole)) {
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToClass(
                    currentRole, authService.getUserId(), securityUtil.getSchoolId(), className, null);
            if (!access.allowed()) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
            effectiveSectionId = access.effectiveSectionId();
        }
        if (minDays < 1 || minDays > 60) return ResponseEntity.badRequest().body("minDays must be between 1 and 60.");
        List<ConsecutiveAbsenceDTO> result =
                attendanceService.getConsecutiveAbsentees(className, minDays, lookbackDays, session, effectiveSectionId);
        return ResponseEntity.ok(result);
    }

    /** GET /api/attendance/summary/school?type=year&session=2025-2026 | ?type=month&month=4&year=2026 */
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/summary/school")
    public ResponseEntity<?> getSchoolAttendanceSummary(@RequestParam(defaultValue = "year") String type,
                                                        @RequestParam(required = false) Integer month,
                                                        @RequestParam(required = false) Integer year,
                                                        @RequestParam(required = false) String session) {
        if (month != null || year != null) {
            ResponseEntity<?> rangeError = validateMonthAndYear(month, year);
            if (rangeError != null) return rangeError;
        }
        return ResponseEntity.ok(attendanceService.getSchoolSummary(type, month, year, session));
    }

    // ─── Insights (read-only, current session) ───────────────────────────

    /** The signed-in student's own attendance insights. */
    @PreAuthorize("hasRole('" + Role.STUDENT + "')")
    @GetMapping("/insights/me")
    public AttendanceInsightsDtos.StudentInsights getMyInsights() {
        return attendanceInsightsService.studentInsights(authService.getUserId());
    }

    /** One student's insights — same access rules as the other per-student endpoints (own/linked child/own class). */
    @GetMapping("/insights/student/{studentId}")
    public ResponseEntity<?> getStudentInsights(@PathVariable String studentId) {
        ResponseEntity<?> deniedResponse = checkStudentDataAccess(studentId);
        if (deniedResponse != null) return deniedResponse;
        return ResponseEntity.ok(attendanceInsightsService.studentInsights(studentId));
    }

    /** A teacher's own class-teacher class/section — no class or section parameters are accepted. */
    @PreAuthorize("hasRole('" + Role.TEACHER + "')")
    @GetMapping("/insights/class")
    public AttendanceInsightsDtos.ClassInsights getMyClassInsights() {
        return attendanceInsightsService.teacherClassInsights();
    }

    /** An admin's insights for one class of their school (sectionId omitted = the whole class). */
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/insights/class/{classId}")
    public AttendanceInsightsDtos.ClassInsights getClassInsights(@PathVariable Long classId,
                                                                 @RequestParam(required = false) Long sectionId) {
        return attendanceInsightsService.adminClassInsights(classId, sectionId);
    }

    /**
     * An unreadable submission body — most importantly a status other than PRESENT/ABSENT (e.g. a
     * legacy HALF_DAY/LATE/EXCUSED client) — is a client error, not a 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Rejected unreadable attendance request: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(Map.of("status", 400, "error", "Bad Request",
                "message", "Invalid attendance request. Each student's status must be PRESENT or ABSENT."));
    }

    /**
     * Per-student endpoints: a STUDENT may only read their own data, a PARENT only a linked child
     * with attendance permission, a TEACHER only a student in their own class/section.
     * Returns a 403 ResponseEntity if access is denied, or null if access is allowed.
     */
    private ResponseEntity<?> checkStudentDataAccess(String studentId) {
        String currentUserId = authService.getUserId();
        String currentRole = authService.getRole();
        if (Role.STUDENT.equals(currentRole) && !studentId.equals(currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Students can only view their own attendance.");
        }
        if (Role.PARENT.equals(currentRole)) {
            parentPortalService.assertChildAccess(studentId, ParentPortalService.ChildPermission.ATTENDANCE);
        }
        if (Role.TEACHER.equals(currentRole)) {
            Long schoolId = securityUtil.getSchoolId();
            Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId).orElse(null);
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToStudent(currentRole, currentUserId, schoolId,
                    student != null ? student.getClassName() : null, student != null ? student.getSectionId() : null);
            if (!access.allowed()) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
        }
        return null;
    }

    /** Validates that month is in [1, 12] and year is in [2000, 2100]. */
    private ResponseEntity<?> validateMonthAndYear(Integer month, Integer year) {
        if (month != null && (month < 1 || month > 12)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid month '" + month + "'. Month must be between 1 and 12."));
        }
        if (year != null && (year < 2000 || year > 2100)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid year '" + year + "'. Year must be between 2000 and 2100."));
        }
        return null;
    }
}
