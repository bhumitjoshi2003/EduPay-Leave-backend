package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import jakarta.validation.Valid;
import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ClassAttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ConsecutiveAbsenceDTO;
import com.indraacademy.ias_management.dto.DailyAttendanceDTO;
import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.service.AttendanceService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.service.TeacherClassScopeService;
import com.indraacademy.ias_management.service.TeacherClassScopeService.ScopedAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/attendance")
@PreAuthorize("isAuthenticated()")
public class AttendanceController {

    private static final Logger log = LoggerFactory.getLogger(AttendanceController.class);

    @Autowired private AttendanceService attendanceService;
    @Autowired private AuthService authService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private com.indraacademy.ias_management.util.SecurityUtil securityUtil;
    @Autowired private ParentPortalService parentPortalService;
    @Autowired private TeacherClassScopeService teacherClassScopeService;

    @PreAuthorize("hasAnyRole('" + Role.TEACHER +  "', '" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<String> saveAttendance(@Valid @RequestBody List<Attendance> attendanceList,
                                                 @RequestParam(required = false) Long sectionId,
                                                 HttpServletRequest request) {
        // TEACHER: every row must be for their own assigned class/section — without this a
        // teacher could write (or overwrite) attendance for a class/section they don't teach.
        // The effective sectionId always comes from the teacher's OWN assignment, never from
        // the sectionId the client sent — see TeacherClassScopeService.
        if (Role.TEACHER.equals(authService.getRole()) && attendanceList != null && !attendanceList.isEmpty()) {
            String requestedClassName = attendanceList.get(0).getClassName();
            boolean allSameClass = attendanceList.stream()
                    .allMatch(a -> requestedClassName != null && requestedClassName.equals(a.getClassName()));
            if (!allSameClass) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Teachers can only save attendance for their assigned class.");
            }
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToClass(
                    authService.getRole(), authService.getUserId(), securityUtil.getSchoolId(),
                    requestedClassName, sectionId);
            if (!access.allowed()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
            }
            sectionId = access.effectiveSectionId();
        }
        log.info("Request to save attendance for {} records.", attendanceList != null ? attendanceList.size() : 0);
        attendanceService.saveAttendance(attendanceList, sectionId, request);
        log.info("Attendance data saved successfully.");
        return ResponseEntity.ok("Attendance data saved successfully.");
    }

    @PreAuthorize("hasAnyRole('" + Role.TEACHER +  "', '" + Role.ADMIN + "')")
    @GetMapping("/date/{absentDate}/class/{className}")
    public ResponseEntity<?> getAttendanceByDateAndClass(
            @PathVariable LocalDate absentDate,
            @PathVariable String className,
            @RequestParam(required = false) Long sectionId) {
        // TEACHER: only their assigned class/section — same rule as getClassAttendanceSummary/
        // getConsecutiveAbsentees below. Effective sectionId always comes from the teacher's own
        // assignment, never the client-supplied value.
        if (Role.TEACHER.equals(authService.getRole())) {
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToClass(
                    authService.getRole(), authService.getUserId(), securityUtil.getSchoolId(), className, sectionId);
            if (!access.allowed()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
            }
            sectionId = access.effectiveSectionId();
        }
        log.info("Request to get attendance for Date: {} and Class: {}", absentDate, className);
        List<Attendance> attendanceList = attendanceService.getAttendanceByDateAndClass(absentDate, className, sectionId);
        return ResponseEntity.ok(attendanceList);
    }

    @GetMapping("/counts/{studentId}/{year}/{month}")
    public ResponseEntity<?> getAttendanceCounts(@PathVariable String studentId, @PathVariable int year, @PathVariable int month) {
        ResponseEntity<?> deniedResponse = checkStudentDataAccess(studentId);
        if (deniedResponse != null) return deniedResponse;
        ResponseEntity<?> rangeError = validateMonthAndYear(month, year);
        if (rangeError != null) return rangeError;
        log.info("Request to get attendance counts for Student: {} in {}-{}", studentId, year, month);
        Map<String, Long> counts = attendanceService.getAttendanceCounts(studentId, year, month);
        return ResponseEntity.ok(counts);
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

    @GetMapping("/unapplied-leave-count/{studentId}/session/{session}")
    public ResponseEntity<?> getTotalUnappliedLeaveCount(
            @PathVariable String studentId,
            @PathVariable String session) {
        ResponseEntity<?> deniedResponse = checkStudentDataAccess(studentId);
        if (deniedResponse != null) return deniedResponse;
        log.info("Request to get unapplied leave count for Student: {} in Session: {}", studentId, session);
        long count = attendanceService.getTotalUnappliedLeaveCount(studentId, session);
        return ResponseEntity.ok(count);
    }

    @PreAuthorize("hasAnyRole('" + Role.TEACHER + "', '" + Role.ADMIN + "')")
    @DeleteMapping("/date/{date}/class/{className}")
    public ResponseEntity<String> deleteAttendanceByDateAndClass(
            @PathVariable LocalDate date,
            @PathVariable String className,
            @RequestParam(required = false) Long sectionId,
            HttpServletRequest request) {
        // TEACHER: only their assigned class/section — same rule as getAttendanceByDateAndClass
        // above; without this a teacher could delete another class/section's attendance.
        if (Role.TEACHER.equals(authService.getRole())) {
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToClass(
                    authService.getRole(), authService.getUserId(), securityUtil.getSchoolId(), className, sectionId);
            if (!access.allowed()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
            }
            sectionId = access.effectiveSectionId();
        }
        log.warn("Request to delete attendance for Date: {} and Class: {}", date, className);
        attendanceService.deleteAttendanceByDateAndClass(date, className, sectionId, request);
        log.info("Attendance records deleted successfully for Date: {} and Class: {}", date, className);
        return ResponseEntity.ok("Attendance records deleted successfully.");
    }

    @GetMapping("/student/{studentId}/month/{month}/year/{year}")
    public ResponseEntity<?> getStudentMonthlyAttendance(
            @PathVariable String studentId,
            @PathVariable int month,
            @PathVariable int year,
            @RequestParam String className) {

        ResponseEntity<?> deniedResponse = checkStudentDataAccess(studentId);
        if (deniedResponse != null) return deniedResponse;
        ResponseEntity<?> rangeError = validateMonthAndYear(month, year);
        if (rangeError != null) return rangeError;
        List<Attendance> list = attendanceService.getAttendanceByStudentClassMonthAndYear(studentId, className, year, month);
        return ResponseEntity.ok(list);
    }

    // ─── Summary endpoints ────────────────────────────────────────────────────

    /**
     * GET /api/attendance/summary/student/{studentId}/daily?month=4&year=2026
     */
    @GetMapping("/summary/student/{studentId}/daily")
    public ResponseEntity<?> getDailyAttendance(
            @PathVariable String studentId,
            @RequestParam int month,
            @RequestParam int year) {

        ResponseEntity<?> deniedResponse = checkStudentDataAccess(studentId);
        if (deniedResponse != null) return deniedResponse;

        String currentUserId = authService.getUserId();
        String currentRole   = authService.getRole();

        // STUDENT/TEACHER scoping is already fully enforced by checkStudentDataAccess above
        // (including the teacher class+section check) — no need to repeat it here.
        if (Role.STUDENT.equals(currentRole) && !studentId.equals(currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Students can only view their own attendance.");
        }

        ResponseEntity<?> rangeError = validateMonthAndYear(month, year);
        if (rangeError != null) return rangeError;

        log.info("Daily attendance request — student: {}, month: {}, year: {}", studentId, month, year);
        DailyAttendanceDTO result = attendanceService.getDailyAttendance(studentId, month, year);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/attendance/summary/student/{studentId}
     *   ?type=month&month=4&year=2026
     *   ?type=year&session=2025-2026
     */
    @GetMapping("/summary/student/{studentId}")
    public ResponseEntity<?> getStudentAttendanceSummary(
            @PathVariable String studentId,
            @RequestParam String type,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String session) {

        ResponseEntity<?> deniedResponse = checkStudentDataAccess(studentId);
        if (deniedResponse != null) return deniedResponse;

        String currentUserId = authService.getUserId();
        String currentRole   = authService.getRole();

        // STUDENT/TEACHER scoping is already fully enforced by checkStudentDataAccess above
        // (including the teacher class+section check) — no need to repeat it here.
        if (Role.STUDENT.equals(currentRole) && !studentId.equals(currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Students can only view their own attendance summary.");
        }

        if (month != null || year != null) {
            ResponseEntity<?> rangeError = validateMonthAndYear(month, year);
            if (rangeError != null) return rangeError;
        }

        log.info("Attendance summary request — student: {}, type: {}, month: {}, year: {}, session: {}",
                studentId, type, month, year, session);
        AttendanceSummaryDTO summary = attendanceService.getStudentSummary(studentId, type, month, year, session);
        return ResponseEntity.ok(summary);
    }

    /**
     * GET /api/attendance/summary/class/{className}
     *   ?type=month&month=4&year=2026
     *   ?type=year&session=2025-2026
     */
    @GetMapping("/summary/class/{className}")
    public ResponseEntity<?> getClassAttendanceSummary(
            @PathVariable String className,
            @RequestParam(defaultValue = "month") String type,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String session,
            @RequestParam(required = false) Long sectionId) {

        String currentUserId = authService.getUserId();
        String currentRole   = authService.getRole();

        // STUDENT: no access to class summary
        if (Role.STUDENT.equals(currentRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Students cannot access class attendance summaries.");
        }

        // PARENT: no access to class-wide data — this endpoint returns every student in the
        // class, which would let a parent see attendance for students they aren't linked to.
        // Parents must use /summary/student/{studentId}, which enforces assertChildAccess.
        if (Role.PARENT.equals(currentRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Parents cannot access class attendance summaries.");
        }

        // TEACHER: only their assigned class/section — effective sectionId always comes from
        // the teacher's own assignment, never the client-supplied value.
        if (Role.TEACHER.equals(currentRole)) {
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToClass(
                    currentRole, currentUserId, securityUtil.getSchoolId(), className, sectionId);
            if (!access.allowed()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
            }
            sectionId = access.effectiveSectionId();
        }

        if (month != null || year != null) {
            ResponseEntity<?> rangeError = validateMonthAndYear(month, year);
            if (rangeError != null) return rangeError;
        }

        log.info("Class attendance summary request — class: {}, type: {}, month: {}, year: {}, session: {}, sectionId: {}",
                className, type, month, year, session, sectionId);
        List<ClassAttendanceSummaryDTO> summary = attendanceService.getClassSummary(className, type, month, year, session, sectionId);
        return ResponseEntity.ok(summary);
    }

    /**
     * GET /api/attendance/consecutive-absentees/class/{className}
     *   ?minDays=3&session=2025-2026&lookbackDays=60
     *
     * Students absent on every one of the last {minDays} *marked school days* for the class —
     * the recent-absence-pattern counterpart to /summary/class/{className}'s cumulative
     * percentage view. Same authorization rules as that endpoint, enforced identically below.
     */
    @GetMapping("/consecutive-absentees/class/{className}")
    public ResponseEntity<?> getConsecutiveAbsentees(
            @PathVariable String className,
            @RequestParam(defaultValue = "3") int minDays,
            @RequestParam(required = false) String session,
            @RequestParam(required = false) Integer lookbackDays) {

        String currentUserId = authService.getUserId();
        String currentRole   = authService.getRole();

        // STUDENT: no access to class-wide data — same rule as getClassAttendanceSummary.
        if (Role.STUDENT.equals(currentRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Students cannot access class attendance summaries.");
        }

        // PARENT: no access to class-wide data — same rule as getClassAttendanceSummary.
        if (Role.PARENT.equals(currentRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Parents cannot access class attendance summaries.");
        }

        // TEACHER: only their assigned class/section. Identical rule to getClassAttendanceSummary
        // — a teacher can never pull another class's (or another section's) absence patterns.
        // This endpoint has no client-facing sectionId parameter at all, so the effective
        // sectionId (when the teacher's class has sections) comes entirely from their own
        // assignment.
        Long effectiveSectionId = null;
        if (Role.TEACHER.equals(currentRole)) {
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToClass(
                    currentRole, currentUserId, securityUtil.getSchoolId(), className, null);
            if (!access.allowed()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
            }
            effectiveSectionId = access.effectiveSectionId();
        }

        if (minDays < 1 || minDays > 60) {
            return ResponseEntity.badRequest().body("minDays must be between 1 and 60.");
        }

        log.info("Consecutive-absentee request — class: {}, minDays: {}, session: {}, lookbackDays: {}",
                className, minDays, session, lookbackDays);
        List<ConsecutiveAbsenceDTO> result =
                attendanceService.getConsecutiveAbsentees(className, minDays, lookbackDays, session, effectiveSectionId);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/attendance/summary/school?type=year&session=2025-2026
     *   ?type=month&month=4&year=2026
     * Same shape as /summary/class/{className} but flattened across every class in
     * the school (className is included per row) — for admin-level comparisons and
     * low-attendance lookups that would otherwise need one request per class.
     */
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/summary/school")
    public ResponseEntity<?> getSchoolAttendanceSummary(
            @RequestParam(defaultValue = "year") String type,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String session) {

        if (month != null || year != null) {
            ResponseEntity<?> rangeError = validateMonthAndYear(month, year);
            if (rangeError != null) return rangeError;
        }

        log.info("School attendance summary request — type: {}, month: {}, year: {}, session: {}", type, month, year, session);
        List<ClassAttendanceSummaryDTO> summary = attendanceService.getSchoolSummary(type, month, year, session);
        return ResponseEntity.ok(summary);
    }

    /**
     * For a per-student endpoint: a STUDENT may only access their own data, and a TEACHER only
     * a student in their own assigned class — same rule already enforced by getDailyAttendance/
     * getStudentAttendanceSummary above, extracted here for reuse by the counts/unapplied-leave/
     * monthly endpoints, which were missing this check entirely (open to any authenticated role,
     * any student).
     * Returns a 403 ResponseEntity if access is denied, or null if access is allowed.
     */
    private ResponseEntity<?> checkStudentDataAccess(String studentId) {
        String currentUserId = authService.getUserId();
        String currentRole = authService.getRole();

        if (Role.STUDENT.equals(currentRole) && !studentId.equals(currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Students can only view their own attendance.");
        }
        if (Role.PARENT.equals(currentRole)) {
            parentPortalService.assertChildAccess(studentId, ParentPortalService.ChildPermission.ATTENDANCE);
        }
        if (Role.TEACHER.equals(currentRole)) {
            Long schoolId = securityUtil.getSchoolId();
            Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId).orElse(null);
            String studentClass = student != null ? student.getClassName() : null;
            Long studentSectionId = student != null ? student.getSectionId() : null;
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToStudent(
                    currentRole, currentUserId, schoolId, studentClass, studentSectionId);
            if (!access.allowed()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
            }
        }
        return null;
    }

    /**
     * Validates that month is in [1, 12] and year is in [2000, 2100].
     * Returns a 400 ResponseEntity if invalid, null if valid.
     */
    private ResponseEntity<?> validateMonthAndYear(Integer month, Integer year) {
        if (month != null && (month < 1 || month > 12)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid month '" + month + "'. Month must be between 1 and 12."));
        }
        if (year != null && (year < 2000 || year > 2100)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid year '" + year + "'. Year must be between 2000 and 2100."));
        }
        return null;
    }
}
