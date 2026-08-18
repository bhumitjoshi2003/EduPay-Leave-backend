package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.AdminMarkTeacherAttendanceRequest;
import com.indraacademy.ias_management.dto.TeacherAttendanceResponse;
import com.indraacademy.ias_management.dto.TeacherAttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.TeacherCheckinRequest;
import com.indraacademy.ias_management.dto.TeacherAttendanceTodaySummaryDTO;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.indraacademy.ias_management.service.TeacherAttendanceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/teacher-checkin")
@PreAuthorize("isAuthenticated()")
public class TeacherAttendanceController {

    private static final Logger log = LoggerFactory.getLogger(TeacherAttendanceController.class);

    @Autowired private TeacherAttendanceService teacherAttendanceService;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private SecurityUtil securityUtil;

    @PreAuthorize("hasRole('" + Role.TEACHER + "')")
    @PostMapping("/check-in")
    public ResponseEntity<?> checkIn(@Valid @RequestBody TeacherCheckinRequest req, HttpServletRequest request) {
        try {
            log.info("Teacher check-in request received");
            TeacherAttendanceResponse response = teacherAttendanceService.checkIn(req.getLatitude(), req.getLongitude(), request);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            log.warn("Check-in rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('" + Role.TEACHER + "')")
    @PostMapping("/check-out")
    public ResponseEntity<?> checkOut(@Valid @RequestBody TeacherCheckinRequest req, HttpServletRequest request) {
        try {
            log.info("Teacher check-out request received");
            TeacherAttendanceResponse response = teacherAttendanceService.checkOut(req.getLatitude(), req.getLongitude(), request);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            log.warn("Check-out rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/admin-mark")
    public ResponseEntity<?> adminMark(@Valid @RequestBody AdminMarkTeacherAttendanceRequest req, HttpServletRequest request) {
        try {
            log.info("Admin marking teacher attendance: teacher={}, date={}, status={}", req.getTeacherId(), req.getDate(), req.getStatus());
            TeacherAttendanceResponse response = teacherAttendanceService.adminMarkAttendance(req, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    // ADMIN-only: this returns EVERY teacher's attendance for the date, school-wide, with no
    // per-caller narrowing (TeacherAttendanceService.getAttendanceByDate is school-scoped only).
    // No TEACHER-facing screen calls this — teacher-checkin.component.ts uses getMyAttendance
    // for self-service; only the admin-only staff-attendance page uses this endpoint. It was
    // previously also open to TEACHER, which let any teacher enumerate every colleague's daily
    // attendance status and check-in/out times.
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/date/{date}")
    public ResponseEntity<List<TeacherAttendanceResponse>> getByDate(@PathVariable LocalDate date) {
        log.info("Fetching teacher attendance for date: {}", date);
        return ResponseEntity.ok(teacherAttendanceService.getAttendanceByDate(date));
    }

    @PreAuthorize("hasRole('" + Role.TEACHER + "')")
    @GetMapping("/my-attendance")
    public ResponseEntity<?> getMyAttendance(@RequestParam int month, @RequestParam int year) {
        if (month < 1 || month > 12 || year < 2000) {
            return ResponseEntity.badRequest().body("Invalid month or year");
        }
        return ResponseEntity.ok(teacherAttendanceService.getMyAttendance(month, year));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/summary")
    public ResponseEntity<?> getSummary(@RequestParam int month, @RequestParam int year,
                                        @RequestParam(required = false) String teacherId) {
        if (month < 1 || month > 12 || year < 2000) {
            return ResponseEntity.badRequest().body("Invalid month or year");
        }
        try {
            return ResponseEntity.ok(teacherId == null || teacherId.isBlank()
                    ? teacherAttendanceService.getSummary(month, year)
                    : teacherAttendanceService.getTeacherSummary(teacherId, month, year));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/summary/session")
    public ResponseEntity<?> getTeacherSessionSummary(@RequestParam String teacherId,
                                                       @RequestParam String session) {
        try {
            return ResponseEntity.ok(teacherAttendanceService.getTeacherSessionSummary(teacherId, session));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/today-summary")
    public ResponseEntity<TeacherAttendanceTodaySummaryDTO> getTodaySummary() {
        return ResponseEntity.ok(teacherAttendanceService.getTodaySummary());
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/school-timing")
    public ResponseEntity<?> getSchoolTiming() {
        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepository.findById(schoolId).orElse(null);
        Map<String, Object> result = new HashMap<>();
        result.put("startTime", school != null && school.getSchoolStartTime() != null
                ? school.getSchoolStartTime().toString() : null);
        result.put("lateThresholdMinutes", school != null && school.getLateThresholdMinutes() != null
                ? school.getLateThresholdMinutes() : 5);
        return ResponseEntity.ok(result);
    }
}
