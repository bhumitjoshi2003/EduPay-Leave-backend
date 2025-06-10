package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.service.AttendanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/attendance")
@CrossOrigin(origins = "http://localhost:4200")
public class AttendanceController {

    @Autowired private AttendanceService attendanceService;

    @PreAuthorize("hasAnyRole('" + Role.TEACHER +  "', '" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<String> saveAttendance(@RequestBody List<Attendance> attendanceList) {
        try {
            attendanceService.saveAttendance(attendanceList);
            return ResponseEntity.ok("Attendance data saved successfully.");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to save attendance data.");
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.TEACHER +  "', '" + Role.ADMIN + "')")
    @GetMapping("/date/{absentDate}/class/{className}")
    public ResponseEntity<List<Attendance>> getAttendanceByDateAndClass(
            @PathVariable LocalDate absentDate,
            @PathVariable String className) {
        List<Attendance> attendanceList = attendanceService.getAttendanceByDateAndClass(absentDate, className);
        return ResponseEntity.ok(attendanceList);
    }

    @GetMapping("/counts/{studentId}/{year}/{month}")
    public ResponseEntity<Map<String, Long>> getAttendanceCounts(@PathVariable String studentId, @PathVariable int year, @PathVariable int month) {
        Map<String, Long> counts = attendanceService.getAttendanceCounts(studentId, year, month);
        return ResponseEntity.ok(counts);
    }

    @GetMapping("/unapplied-leave-count/{studentId}/session/{session}")
    public ResponseEntity<Long> getTotalUnappliedLeaveCount(
            @PathVariable String studentId,
            @PathVariable String session) {
        long count = attendanceService.getTotalUnappliedLeaveCount(studentId, session);
        return ResponseEntity.ok(count);
    }
}