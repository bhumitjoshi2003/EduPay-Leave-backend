package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.AssessmentGroupDTO;
import com.indraacademy.ias_management.dto.AssessmentGroupRequest;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO;
import com.indraacademy.ias_management.service.AssessmentGroupService;
import com.indraacademy.ias_management.service.WeightageCalculationEngine;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/assessment-groups")
public class AssessmentGroupController {

    private static final Logger log = LoggerFactory.getLogger(AssessmentGroupController.class);

    @Autowired private AssessmentGroupService groupService;
    @Autowired private WeightageCalculationEngine weightageEngine;
    @Autowired private com.indraacademy.ias_management.util.SecurityUtil securityUtil;
    @Autowired private com.indraacademy.ias_management.repository.StudentRepository studentRepository;
    @Autowired private com.indraacademy.ias_management.service.TeacherClassScopeService teacherClassScopeService;

    // ── List all groups for a session + class ──────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<?> getGroups(
            @RequestParam String session,
            @RequestParam String className) {
        log.info("GET /api/assessment-groups?session={}&className={}", session, className);
        try {
            List<AssessmentGroupDTO> groups = groupService.getGroups(session, className);
            return ResponseEntity.ok(groups);
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        }
    }

    // ── Get single group ───────────────────────────────────────────────

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<?> getGroup(@PathVariable Long id) {
        log.info("GET /api/assessment-groups/{}", id);
        try {
            return ResponseEntity.ok(groupService.getGroup(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        }
    }

    // ── Create ─────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<?> createGroup(@Valid @RequestBody AssessmentGroupRequest req) {
        log.info("POST /api/assessment-groups name='{}' session={}", req.getName(), req.getSession());
        try {
            AssessmentGroupDTO created = groupService.createGroup(req);
            return new ResponseEntity<>(created, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        }
    }

    // ── Update ─────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<?> updateGroup(@PathVariable Long id,
                                          @Valid @RequestBody AssessmentGroupRequest req) {
        log.info("PUT /api/assessment-groups/{}", id);
        try {
            AssessmentGroupDTO updated = groupService.updateGroup(id, req);
            return ResponseEntity.ok(updated);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        }
    }

    // ── Delete ─────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<?> deleteGroup(@PathVariable Long id) {
        log.info("DELETE /api/assessment-groups/{}", id);
        try {
            groupService.deleteGroup(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        }
    }

    // ── Compute weighted result for one student ────────────────────────

    @GetMapping("/{id}/compute")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<?> computeForStudent(
            @PathVariable Long id,
            @RequestParam String studentId,
            @RequestParam String session) {
        log.info("GET /api/assessment-groups/{}/compute?studentId={}&session={}", id, studentId, session);
        // TEACHER: only a student of their own class-teacher class/section (previously unscoped —
        // any teacher could compute any student's result in the school).
        if (Role.TEACHER.equals(securityUtil.getRole())) {
            Long schoolId = securityUtil.getSchoolId();
            var student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId).orElse(null);
            var access = teacherClassScopeService.authorizeAndScopeToStudent(Role.TEACHER, securityUtil.getUsername(),
                    schoolId, student != null ? student.getClassName() : null, student != null ? student.getSectionId() : null);
            if (student == null || !access.allowed()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message",
                        access.errorMessage() != null ? access.errorMessage() : "You can only view results for students in your own class."));
            }
        }
        try {
            WeightedGroupResultDTO result = weightageEngine.computeForStudent(studentId, id, session);
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        }
    }
}
