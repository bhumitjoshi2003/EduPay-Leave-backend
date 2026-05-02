package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ClassAttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.DailyAttendanceDTO;
import com.indraacademy.ias_management.entity.Attendance;
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
@CrossOrigin(origins = "http://localhost:4200")
public class AttendanceController {

    private static final Logger log = LoggerFactory.getLogger(AttendanceController.class);

    @Autowired private AttendanceService attendanceService;
    @Autowired private AuthService authService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherRepository teacherRepository;

    @PreAuthorize("hasAnyRole('" + Role.TEACHER +  "', '" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<String> saveAttendance(@RequestBody List<Attendance> attendanceList, HttpServletRequest request) {
        log.info("Request to save attendance for {} records.", attendanceList != null ? attendanceList.size() : 0);
        attendanceService.saveAttendance(attendanceList, request);
        log.info("Attendance data saved successfully.");
        return ResponseEntity.ok("Attendance data saved successfully.");
    }

    @PreAuthorize("hasAnyRole('" + Role.TEACHER +  "', '" + Role.ADMIN + "')")
    @GetMapping("/date/{absentDate}/class/{className}")
    public ResponseEntity<List<Attendance>> getAttendanceByDateAndClass(
            @PathVariable LocalDate absentDate,
            @PathVariable String className) {
        log.info("Request to get attendance for Date: {} and Class: {}", absentDate, className);
        List<Attendance> attendanceList = attendanceService.getAttendanceByDateAndClass(absentDate, className);
        return ResponseEntity.ok(attendanceList);
    }

    @GetMapping("/counts/{studentId}/{year}/{month}")
    public ResponseEntity<Map<String, Long>> getAttendanceCounts(@PathVariable String studentId, @PathVariable int year, @PathVariable int month) {
        log.info("Request to get attendance counts for Student: {} in {}-{}", studentId, year, month);
        Map<String, Long> counts = attendanceService.getAttendanceCounts(studentId, year, month);
        return ResponseEntity.ok(counts);
    }

    @GetMapping("/unapplied-leave-count/{studentId}/session/{session}")
    public ResponseEntity<Long> getTotalUnappliedLeaveCount(
            @PathVariable String studentId,
            @PathVariable String session) {
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
        log.warn("Request to delete attendance for Date: {} and Class: {}", date, className);
        attendanceService.deleteAttendanceByDateAndClass(date, className, request);
        log.info("Attendance records deleted successfully for Date: {} and Class: {}", date, className);
        return ResponseEntity.ok("Attendance records deleted successfully.");
    }

    @GetMapping("/student/{studentId}/month/{month}/year/{year}")
    public ResponseEntity<List<Attendance>> getStudentMonthlyAttendance(
            @PathVariable String studentId,
            @PathVariable int month,
            @PathVariable int year,
            @RequestParam String className) {

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
            String teacherClass = teacherRepository.findById(currentUserId)
                    .map(Teacher::getClassTeacher).orElse(null);
            String studentClass = studentRepository.findByStudentId(studentId)
                    .map(s -> s.getClassName()).orElse(null);
            if (teacherClass == null || !teacherClass.equals(studentClass)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Teachers can only view attendance for students in their assigned class.");
            }
        }

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
            String teacherClass = teacherRepository.findById(currentUserId)
                    .map(Teacher::getClassTeacher).orElse(null);
            String studentClass = studentRepository.findByStudentId(studentId)
                    .map(s -> s.getClassName()).orElse(null);
            if (teacherClass == null || !teacherClass.equals(studentClass)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Teachers can only view attendance for students in their assigned class.");
            }
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
            @RequestParam(required = false) String session) {

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

        log.info("Class attendance summary request — class: {}, type: {}, month: {}, year: {}, session: {}",
                className, type, month, year, session);
        List<ClassAttendanceSummaryDTO> summary = attendanceService.getClassSummary(className, type, month, year, session);
        return ResponseEntity.ok(summary);
    }
}