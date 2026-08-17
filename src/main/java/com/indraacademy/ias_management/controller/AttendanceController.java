package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import jakarta.validation.Valid;
import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ClassAttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ConsecutiveAbsenceDTO;
import com.indraacademy.ias_management.dto.DailyAttendanceDTO;
import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.service.AttendanceService;
import com.indraacademy.ias_management.service.AuthService;
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
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private com.indraacademy.ias_management.util.SecurityUtil securityUtil;

    @PreAuthorize("hasAnyRole('" + Role.TEACHER +  "', '" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<String> saveAttendance(@Valid @RequestBody List<Attendance> attendanceList, HttpServletRequest request) {
        // TEACHER: every row must be for their own assigned class — without this a teacher
        // could write (or overwrite) attendance for a class they don't teach.
        if (Role.TEACHER.equals(authService.getRole()) && attendanceList != null) {
            String teacherClass = teacherRepository.findById(authService.getUserId())
                    .map(Teacher::getClassTeacher).orElse(null);
            boolean allOwnClass = attendanceList.stream()
                    .allMatch(a -> teacherClass != null && teacherClass.equals(a.getClassName()));
            if (!allOwnClass) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Teachers can only save attendance for their assigned class.");
            }
        }
        log.info("Request to save attendance for {} records.", attendanceList != null ? attendanceList.size() : 0);
        attendanceService.saveAttendance(attendanceList, request);
        log.info("Attendance data saved successfully.");
        return ResponseEntity.ok("Attendance data saved successfully.");
    }

    @PreAuthorize("hasAnyRole('" + Role.TEACHER +  "', '" + Role.ADMIN + "')")
    @GetMapping("/date/{absentDate}/class/{className}")
    public ResponseEntity<?> getAttendanceByDateAndClass(
            @PathVariable LocalDate absentDate,
            @PathVariable String className) {
        // TEACHER: only their assigned class — same check as getClassAttendanceSummary/
        // getConsecutiveAbsentees below, applied here too since this endpoint was missing it.
        if (Role.TEACHER.equals(authService.getRole())) {
            String teacherClass = teacherRepository.findById(authService.getUserId())
                    .map(Teacher::getClassTeacher).orElse(null);
            if (!className.equals(teacherClass)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Teachers can only view attendance for their assigned class.");
            }
        }
        log.info("Request to get attendance for Date: {} and Class: {}", absentDate, className);
        List<Attendance> attendanceList = attendanceService.getAttendanceByDateAndClass(absentDate, className);
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
            HttpServletRequest request) {
        // TEACHER: only their assigned class — same check as getAttendanceByDateAndClass above;
        // without this a teacher could delete another class's attendance for a day.
        if (Role.TEACHER.equals(authService.getRole())) {
            String teacherClass = teacherRepository.findById(authService.getUserId())
                    .map(Teacher::getClassTeacher).orElse(null);
            if (!className.equals(teacherClass)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Teachers can only delete attendance for their assigned class.");
            }
        }
        log.warn("Request to delete attendance for Date: {} and Class: {}", date, className);
        attendanceService.deleteAttendanceByDateAndClass(date, className, request);
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

        String currentUserId = authService.getUserId();
        String currentRole   = authService.getRole();

        if (Role.STUDENT.equals(currentRole) && !studentId.equals(currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Students can only view their own attendance.");
        }
        if (Role.TEACHER.equals(currentRole)) {
            Long schoolId = securityUtil.getSchoolId();
            String teacherClass = teacherRepository.findById(currentUserId)
                    .map(Teacher::getClassTeacher).orElse(null);
            String studentClass = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                    .map(s -> s.getClassName()).orElse(null);
            if (teacherClass == null || !teacherClass.equals(studentClass)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Teachers can only view attendance for students in their assigned class.");
            }
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

        String currentUserId = authService.getUserId();
        String currentRole   = authService.getRole();

        // STUDENT: only own profile
        if (Role.STUDENT.equals(currentRole) && !studentId.equals(currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Students can only view their own attendance summary.");
        }

        // TEACHER: only students in their assigned class
        if (Role.TEACHER.equals(currentRole)) {
            Long schoolId = securityUtil.getSchoolId();
            String teacherClass = teacherRepository.findById(currentUserId)
                    .map(Teacher::getClassTeacher).orElse(null);
            String studentClass = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                    .map(s -> s.getClassName()).orElse(null);
            if (teacherClass == null || !teacherClass.equals(studentClass)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Teachers can only view attendance for students in their assigned class.");
            }
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

        // TEACHER: only their assigned class
        if (Role.TEACHER.equals(currentRole)) {
            String teacherClass = teacherRepository.findById(currentUserId)
                    .map(Teacher::getClassTeacher).orElse(null);
            if (!className.equals(teacherClass)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Teachers can only view attendance for their assigned class.");
            }
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

        // TEACHER: only their assigned class. Identical check to getClassAttendanceSummary —
        // a teacher can never pull another class's absence patterns, whatever className is sent.
        if (Role.TEACHER.equals(currentRole)) {
            String teacherClass = teacherRepository.findById(currentUserId)
                    .map(Teacher::getClassTeacher).orElse(null);
            if (!className.equals(teacherClass)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Teachers can only view attendance for their assigned class.");
            }
        }

        if (minDays < 1 || minDays > 60) {
            return ResponseEntity.badRequest().body("minDays must be between 1 and 60.");
        }

        log.info("Consecutive-absentee request — class: {}, minDays: {}, session: {}, lookbackDays: {}",
                className, minDays, session, lookbackDays);
        List<ConsecutiveAbsenceDTO> result =
                attendanceService.getConsecutiveAbsentees(className, minDays, lookbackDays, session);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/attendance/summary/school?type=year&session=2025-2026
     *   ?type=month&month=4&year=2026
     * Same shape as /summary/class/{className} but flattened across every class in
     * the school (className is included per row) — for admin-level comparisons and
     * low-attendance lookups that would otherwise need one request per class.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
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
        if (Role.TEACHER.equals(currentRole)) {
            Long schoolId = securityUtil.getSchoolId();
            String teacherClass = teacherRepository.findById(currentUserId)
                    .map(Teacher::getClassTeacher).orElse(null);
            String studentClass = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                    .map(Student::getClassName).orElse(null);
            if (teacherClass == null || !teacherClass.equals(studentClass)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Teachers can only view attendance for students in their assigned class.");
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