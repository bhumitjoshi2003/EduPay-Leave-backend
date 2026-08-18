package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.exception.InvalidLeaveStatusTransitionException;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.LeaveService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
public class LeaveController {

    private static final Logger log = LoggerFactory.getLogger(LeaveController.class);

    @Autowired private LeaveService leaveService;
    @Autowired private AuthService authService;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private SecurityUtil securityUtil;

    @PreAuthorize("hasAnyRole('" + Role.STUDENT + "')")
    @PostMapping("/apply-leave")
    public ResponseEntity<String> applyLeave(@Valid @RequestBody Leave leave, HttpServletRequest request) {
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
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "', '" + Role.SUB_ADMIN + "')")
    public ResponseEntity<?> getLeaves(
            @RequestParam(required = false)  String className,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) LeaveStatus status,
            Pageable pageable
    ) {
        // TEACHER: always their own assigned class — overridden, never merged with whatever
        // (if anything) the client sent, same rule already enforced on /for-review. Without
        // this, a teacher with no className in the request (e.g. the empty-string case) fell
        // through to an unscoped, school-wide leave search.
        String effectiveClassName = className;
        if (Role.TEACHER.equals(authService.getRole())) {
            String ownClass = teacherRepository.findByTeacherIdAndSchoolId(authService.getUserId(), securityUtil.getSchoolId())
                    .map(Teacher::getClassTeacher).orElse(null);
            if (ownClass == null || ownClass.isBlank()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You are not assigned as a class teacher."));
            }
            effectiveClassName = ownClass;
        }
        log.info("Request to get filtered leaves. Class: {}, Student: {}, Date: {}", effectiveClassName, studentId, date);
        return ResponseEntity.ok(
                leaveService.getLeavesFiltered(effectiveClassName, studentId, date, status, pageable)
        );
    }

    @GetMapping("/student/{studentId}")
    @PreAuthorize("hasAnyRole('" + Role.STUDENT + "', '" + Role.ADMIN + "')")
    public ResponseEntity<Page<Leave>> getLeavesOfStudent(
            @PathVariable String studentId,
            Pageable pageable) {

        // Pulls the studentId from the SecurityContext to prevent users from querying other students' leaves.
        String authenticatedStudentId = authService.getUserId();
        log.info("Request to get leaves for student ID: {} (authenticated as {})", studentId, authenticatedStudentId);

        return ResponseEntity.ok(leaveService.getLeavesByStudentId(authenticatedStudentId, pageable));
    }

    @GetMapping("/date/{date}/class/{className}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "', '" + Role.SUB_ADMIN + "')")
    public ResponseEntity<?> getLeavesByDateAndClass(@PathVariable String date, @PathVariable String className) {
        // TEACHER: only their assigned class — same rule as getLeaves above.
        if (Role.TEACHER.equals(authService.getRole())) {
            String ownClass = teacherRepository.findByTeacherIdAndSchoolId(authService.getUserId(), securityUtil.getSchoolId())
                    .map(Teacher::getClassTeacher).orElse(null);
            if (ownClass == null || !ownClass.equals(className)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Teachers can only view leaves for their assigned class."));
            }
        }
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
        try {
            Leave updated = leaveService.updateLeaveStatus(leaveId, status, request);
            return ResponseEntity.ok(updated);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (InvalidLeaveStatusTransitionException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * GET /api/leaves/for-review?className=&studentId=&ids=1,2,3&limit=25
     *
     * <p>The read surface behind the AI Copilot's leave tools and its decision workflow. Two modes:
     * with {@code ids}, it resolves exactly those requests <i>whatever their current status</i>, so
     * the caller can be told "already approved" instead of silently losing the request; without,
     * it returns the PENDING review queue, oldest first.
     *
     * <p><b>Teachers are confined to their own class here</b>, unlike this module's older endpoints
     * which are school-scoped for every staff role. That difference is deliberate and limited to
     * this new surface: natural language makes broad action cheap, so the Copilot is held to the
     * same class confinement the attendance tools already enforce, without changing the behaviour
     * of the existing View Leaves screen.
     */
    @GetMapping("/for-review")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<?> getLeavesForReview(
            @RequestParam(required = false) String className,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) List<Long> ids,
            @RequestParam(defaultValue = "25") int limit) {

        String role = authService.getRole();
        String effectiveClassName = className;

        if (Role.TEACHER.equals(role)) {
            String ownClass = teacherRepository.findByTeacherIdAndSchoolId(
                            authService.getUserId(), securityUtil.getSchoolId())
                    .map(Teacher::getClassTeacher).orElse(null);
            if (ownClass == null || ownClass.isBlank()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You are not assigned as a class teacher."));
            }
            // Overridden, never merged — a className in the query string can only ever narrow to
            // the teacher's own class, never select a different one.
            effectiveClassName = ownClass;
        }

        List<Leave> result;
        if (ids != null && !ids.isEmpty()) {
            final String scope = effectiveClassName;
            result = leaveService.getLeavesByIds(ids).stream()
                    .filter(l -> scope == null || scope.equals(l.getClassName()))
                    .toList();
        } else {
            result = leaveService.getLeavesForReview(effectiveClassName, studentId, limit);
        }
        return ResponseEntity.ok(result);
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @DeleteMapping("/{leaveId}")
    public ResponseEntity<String> deleteLeaveById(@PathVariable Long leaveId, HttpServletRequest request) {
        log.warn("Request to delete leave by ID: {}", leaveId);
        try {
            leaveService.deleteLeaveById(leaveId, request);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
        log.info("Leave application deleted successfully by ID: {}", leaveId);
        return new ResponseEntity<>("Leave application deleted successfully", HttpStatus.OK);
    }
}
