package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.entity.Payment;
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

@RestController
@RequestMapping("/leaves")
@CrossOrigin(origins = "http://localhost:4200")
public class LeaveController {

    private static final Logger logger = LoggerFactory.getLogger(LeaveController.class);

    @Autowired private LeaveService leaveService;
    @Autowired private AuthService authService;

    @PreAuthorize("hasAnyRole('" + Role.STUDENT + "')")
    @PostMapping("/apply-leave")
    public ResponseEntity<String> applyLeave(@RequestBody Leave leave, @RequestHeader(name = "Authorization") String authorizationHeader) {
        String studentId = authService.getUserIdFromToken(authorizationHeader);
        leave.setStudentId(studentId);
        leaveService.applyLeave(leave);
        return ResponseEntity.ok("Leave applied successfully");
    }

    @PreAuthorize("hasAnyRole('" + Role.STUDENT + "', '" + Role.ADMIN + "')")
    @DeleteMapping("/delete/{studentId}/{leaveDate}")
    public ResponseEntity<String> deleteLeave(@PathVariable String studentId, @PathVariable String leaveDate, @RequestHeader(name = "Authorization") String authorizationHeader) {
        String userId = authService.getUserIdFromToken(authorizationHeader);
        String role = authService.getRoleFromToken(authorizationHeader);
        if(role.equals("STUDENT")) {
            studentId = userId;
        }
        leaveService.deleteLeave(studentId, leaveDate);
        return new ResponseEntity<>("Leave deleted successfully", HttpStatus.OK);
    }

    @GetMapping("/student")
    public ResponseEntity<Page<Leave>> getLeaves(
            @RequestParam(required = false)  String className,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String date,
            Pageable pageable
    ) {
        return ResponseEntity.ok(
                leaveService.getLeavesFiltered(className, studentId, date, pageable)
        );
    }

    @GetMapping("/student/{studentId}")
    public ResponseEntity<Page<Leave>> getLeavesOfStudent(
            @PathVariable String studentId,
            Pageable pageable,
            @RequestHeader(name = "Authorization") String authorizationHeader) {
        studentId = authService.getUserIdFromToken(authorizationHeader);
        return ResponseEntity.ok(leaveService.getLeavesByStudentId(studentId, pageable));
    }

    @GetMapping("/date/{date}/class/{className}")
    public ResponseEntity<List<String>> getLeavesByDateAndClass(@PathVariable String date, @PathVariable String className) {
        List<String> leaves = leaveService.getLeavesByDateAndClass(date, className);
        return new ResponseEntity<>(leaves, HttpStatus.OK);
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @DeleteMapping("/{leaveId}")
    public ResponseEntity<String> deleteLeaveById(@PathVariable String leaveId) {
        Optional<Leave> leaveOptional = leaveService.getLeaveById(leaveId); // getLeaveById will also need String parameter
        if (leaveOptional.isEmpty()) {
            return new ResponseEntity<>("Leave application not found", HttpStatus.NOT_FOUND);
        }
        leaveService.deleteLeaveById(leaveId); // deleteLeaveById will also need String parameter
        return new ResponseEntity<>("Leave application deleted successfully", HttpStatus.OK);
    }
}