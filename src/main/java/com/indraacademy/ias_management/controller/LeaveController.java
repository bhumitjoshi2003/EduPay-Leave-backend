package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.LeaveService;
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
import java.util.Optional;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/leaves")
@CrossOrigin(origins = "http://localhost:4200")
public class LeaveController {

    private static final Logger log = LoggerFactory.getLogger(LeaveController.class);

    @Autowired private LeaveService leaveService;
    @Autowired private AuthService authService;

    @PreAuthorize("hasAnyRole('" + Role.STUDENT + "')")
    @PostMapping("/apply-leave")
    public ResponseEntity<String> applyLeave(@RequestBody Leave leave, @RequestHeader(name = "Authorization") String authorizationHeader) {
        String studentId = authService.getUserIdFromToken(authorizationHeader);
        log.info("Request to apply leave for student ID: {}", studentId);

        try {
            leave.setStudentId(studentId);
            leaveService.applyLeave(leave);
            return ResponseEntity.ok("Leave applied successfully");
        } catch (IllegalArgumentException e) {
            log.error("Bad request applying leave for student {}: {}", studentId, e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error applying leave for student {}.", studentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to apply leave.");
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.STUDENT + "', '" + Role.ADMIN + "')")
    @DeleteMapping("/delete/{studentId}/{leaveDate}")
    public ResponseEntity<String> deleteLeave(@PathVariable String studentId, @PathVariable String leaveDate, @RequestHeader(name = "Authorization") String authorizationHeader) {
        String userId = authService.getUserIdFromToken(authorizationHeader);
        String role = authService.getRoleFromToken(authorizationHeader);
        log.warn("Request to delete leave for {} on {} by user {} ({})", studentId, leaveDate, userId, role);

        String finalStudentId = studentId;
        if(Role.STUDENT.equals(role)) {
            finalStudentId = userId;
        }

        try {
            leaveService.deleteLeave(finalStudentId, leaveDate);
            log.info("Leave deleted successfully for student {} on {}", finalStudentId, leaveDate);
            return new ResponseEntity<>("Leave deleted successfully", HttpStatus.OK);
        } catch (NoSuchElementException e) {
            log.error("Leave not found for deletion (Student: {}, Date: {}).", finalStudentId, leaveDate);
            return new ResponseEntity<>("Leave application not found", HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException e) {
            log.error("Bad request deleting leave for student {}: {}", finalStudentId, e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/student")
    public ResponseEntity<Page<Leave>> getLeaves(
            @RequestParam(required = false)  String className,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String date,
            Pageable pageable
    ) {
        log.info("Request to get filtered leaves. Class: {}, Student: {}, Date: {}", className, studentId, date);
        try {
            return ResponseEntity.ok(
                    leaveService.getLeavesFiltered(className, studentId, date, pageable)
            );
        } catch (IllegalArgumentException e) {
            log.error("Invalid parameters for filtered leaves: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/student/{studentId}")
    public ResponseEntity<Page<Leave>> getLeavesOfStudent(
            @PathVariable String studentId,
            Pageable pageable,
            @RequestHeader(name = "Authorization") String authorizationHeader) {

        // This method forces the studentId to be pulled from the token, ignoring the path variable, which seems intended for security.
        String authenticatedStudentId = authService.getUserIdFromToken(authorizationHeader);
        log.info("Request to get leaves for student ID: {} (authenticated as {})", studentId, authenticatedStudentId);

        try {
            return ResponseEntity.ok(leaveService.getLeavesByStudentId(authenticatedStudentId, pageable));
        } catch (IllegalArgumentException e) {
            log.error("Invalid parameters for student leaves (ID: {}): {}", authenticatedStudentId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/date/{date}/class/{className}")
    public ResponseEntity<List<String>> getLeavesByDateAndClass(@PathVariable String date, @PathVariable String className) {
        log.info("Request to get leaves by Date: {} and Class: {}", date, className);
        try {
            List<String> leaves = leaveService.getLeavesByDateAndClass(date, className);
            return new ResponseEntity<>(leaves, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            log.error("Invalid parameters for leaves by date/class: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @DeleteMapping("/{leaveId}")
    public ResponseEntity<String> deleteLeaveById(@PathVariable String leaveId) {
        log.warn("Request to delete leave by ID: {}", leaveId);
        try {
            leaveService.deleteLeaveById(leaveId);
            log.info("Leave application deleted successfully by ID: {}", leaveId);
            return new ResponseEntity<>("Leave application deleted successfully", HttpStatus.OK);
        } catch (NoSuchElementException e) {
            log.error("Leave application not found for ID: {}.", leaveId);
            return new ResponseEntity<>("Leave application not found", HttpStatus.NOT_FOUND);
        }
    }
}