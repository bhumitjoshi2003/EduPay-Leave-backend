package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.service.AttendanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/attendance")
@CrossOrigin(origins = "http://localhost:4200") // Allow requests from your Angular app
public class AttendanceController {

    @Autowired
    private AttendanceService attendanceService;

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

    @GetMapping("/date/{absentDate}/class/{className}")
    public ResponseEntity<List<Attendance>> getAttendanceByDateAndClass(
            @PathVariable LocalDate absentDate,
            @PathVariable String className) {
        List<Attendance> attendanceList = attendanceService.getAttendanceByDateAndClass(absentDate, className);
        return ResponseEntity.ok(attendanceList);
    }
}