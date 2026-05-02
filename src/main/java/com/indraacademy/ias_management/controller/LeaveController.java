package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.LeaveService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/leaves")
@CrossOrigin(origins = "http://localhost:4200")
public class LeaveController {

    private static final Logger log = LoggerFactory.getLogger(LeaveController.class);

    @Autowired private LeaveService leaveService;
    @Autowired private AuthService authService;

    @PreAuthorize("hasAnyRole('" + Role.STUDENT + "')")
    @PostMapping("/apply-leave")
    public ResponseEntity<String> applyLeave(@RequestBody Leave leave, HttpServletRequest request) {
        String studentId = authService.getUserId();
        log.info("Request to apply leave for student ID: {}", studentId);
        leave.setStudentId(studentId);
        leaveService.applyLeave(leave, request);
        return ResponseEntity.ok("Leave applied successfully");
    }

    @PreAuthorize("hasAnyRole('" + Role.STUDENT + "', '" + Role.ADMIN + "')")
    @DeleteMapping("/delete/{studentId}/{leaveDate}")
    public ResponseEntity<String> deleteLeave(@PathVariable String studentId, @PathVariable String leaveDate, HttpServletRequest request) {
        String userId = authService.getUserId();
        String role = authService.getRole();
        log.warn("Request to delete leave for {} on {} by user {} ({})", studentId, leaveDate, userId, role);

        String finalStudentId = studentId;
        if(Role.STUDENT.equals(role)) {
            finalStudentId = userId;
        }

        leaveService.deleteLeave(finalStudentId, leaveDate, request);
        log.info("Leave deleted successfully for student {} on {}", finalStudentId, leaveDate);
        return new ResponseEntity<>("Leave deleted successfully", HttpStatus.OK);
    }

    @GetMapping("/student")
    public ResponseEntity<Page<Leave>> getLeaves(
            @RequestParam(required = false)  String className,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String date,
            Pageable pageable
    ) {
        log.info("Request to get filtered leaves. Class: {}, Student: {}, Date: {}", className, studentId, date);
        return ResponseEntity.ok(
                leaveService.getLeavesFiltered(className, studentId, date, pageable)
        );
    }

    @GetMapping("/student/{studentId}")
    public ResponseEntity<Page<Leave>> getLeavesOfStudent(
            @PathVariable String studentId,
            Pageable pageable) {

        // Pulls the studentId from the SecurityContext to prevent users from querying other students' leaves.
        String authenticatedStudentId = authService.getUserId();
        log.info("Request to get leaves for student ID: {} (authenticated as {})", studentId, authenticatedStudentId);

        return ResponseEntity.ok(leaveService.getLeavesByStudentId(authenticatedStudentId, pageable));
    }

    @GetMapping("/date/{date}/class/{className}")
    public ResponseEntity<List<String>> getLeavesByDateAndClass(@PathVariable String date, @PathVariable String className) {
        log.info("Request to get leaves by Date: {} and Class: {}", date, className);
        List<String> leaves = leaveService.getLeavesByDateAndClass(date, className);
        return new ResponseEntity<>(leaves, HttpStatus.OK);
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @PatchMapping("/{leaveId}/status")
    public ResponseEntity<?> updateLeaveStatus(
            @PathVariable Long leaveId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        log.info("Request to update status of leave ID: {}", leaveId);
        String statusValue = body.get("status");
        if (statusValue == null) {
            return ResponseEntity.badRequest().body("Missing 'status' field.");
        }
        LeaveStatus status;
        try {
            status = LeaveStatus.valueOf(statusValue.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid status value: " + statusValue);
        }
        Leave updated = leaveService.updateLeaveStatus(leaveId, status, request);
        return ResponseEntity.ok(updated);
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @DeleteMapping("/{leaveId}")
    public ResponseEntity<String> deleteLeaveById(@PathVariable Long leaveId, HttpServletRequest request) {
        log.warn("Request to delete leave by ID: {}", leaveId);
        leaveService.deleteLeaveById(leaveId, request);
        log.info("Leave application deleted successfully by ID: {}", leaveId);
        return new ResponseEntity<>("Leave application deleted successfully", HttpStatus.OK);
    }
}