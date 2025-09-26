package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.service.AttendanceService;
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
import java.util.NoSuchElementException;


@RestController
@RequestMapping("/attendance")
@CrossOrigin(origins = "http://localhost:4200")
public class AttendanceController {

    private static final Logger log = LoggerFactory.getLogger(AttendanceController.class);

    @Autowired private AttendanceService attendanceService;

    @PreAuthorize("hasAnyRole('" + Role.TEACHER +  "', '" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<String> saveAttendance(@RequestBody List<Attendance> attendanceList) {
        log.info("Request to save attendance for {} records.", attendanceList != null ? attendanceList.size() : 0);
        try {
            attendanceService.saveAttendance(attendanceList);
            log.info("Attendance data saved successfully.");
            return ResponseEntity.ok("Attendance data saved successfully.");
        } catch (IllegalArgumentException e) {
            log.error("Invalid data provided for saving attendance: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to save attendance data.", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to save attendance data.");
        }
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
        try {
            Map<String, Long> counts = attendanceService.getAttendanceCounts(studentId, year, month);
            return ResponseEntity.ok(counts);
        } catch (IllegalArgumentException e) {
            log.error("Invalid parameters for attendance counts (Student: {}, Year: {}, Month: {}): {}", studentId, year, month, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/unapplied-leave-count/{studentId}/session/{session}")
    public ResponseEntity<Long> getTotalUnappliedLeaveCount(
            @PathVariable String studentId,
            @PathVariable String session) {
        log.info("Request to get unapplied leave count for Student: {} in Session: {}", studentId, session);
        try {
            long count = attendanceService.getTotalUnappliedLeaveCount(studentId, session);
            return ResponseEntity.ok(count);
        } catch (IllegalArgumentException e) {
            log.error("Invalid parameters for unapplied leave count (Student: {}, Session: {}): {}", studentId, session, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.TEACHER + "', '" + Role.ADMIN + "')")
    @DeleteMapping("/date/{date}/class/{className}")
    public ResponseEntity<String> deleteAttendanceByDateAndClass(
            @PathVariable LocalDate date,
            @PathVariable String className) {
        log.warn("Request to delete attendance for Date: {} and Class: {}", date, className);
        try {
            attendanceService.deleteAttendanceByDateAndClass(date, className);
            log.info("Attendance records deleted successfully for Date: {} and Class: {}", date, className);
            return ResponseEntity.ok("Attendance records deleted successfully.");
        } catch (IllegalArgumentException e) {
            log.error("Invalid parameters for attendance deletion (Date: {}, Class: {}): {}", date, className, e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete attendance records for Date: {} and Class: {}.", date, className, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to delete attendance records.");
        }
    }
}