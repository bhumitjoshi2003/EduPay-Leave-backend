package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ElectiveEnrollmentDTO;
import com.indraacademy.ias_management.service.StudentElectiveEnrollmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Endpoints for managing student elective (optional-subject) enrollment for classes 1–10.
 *
 * GET    /api/elective-enrollment/class/{className}                — get all enrollments for a class
 * POST   /api/elective-enrollment                                  — enroll/update one student
 * DELETE /api/elective-enrollment/{studentId}/{className}/{group}  — unenroll one student from a group
 * POST   /api/elective-enrollment/bulk                             — bulk-assign many students
 */
@RestController
@RequestMapping("/api/elective-enrollment")
@PreAuthorize("hasRole('" + Role.ADMIN + "')")
public class StudentElectiveEnrollmentController {

    private static final Logger log = LoggerFactory.getLogger(StudentElectiveEnrollmentController.class);

    @Autowired private StudentElectiveEnrollmentService service;

    @GetMapping("/class/{className}")
    public ResponseEntity<List<ElectiveEnrollmentDTO>> getForClass(@PathVariable String className) {
        log.info("GET /api/elective-enrollment/class/{}", className);
        return ResponseEntity.ok(service.getEnrollmentsForClass(className));
    }

    @PostMapping
    public ResponseEntity<?> enroll(@RequestBody Map<String, String> body) {
        String studentId    = body.get("studentId");
        String className    = body.get("className");
        String optionalGroup = body.get("optionalGroup");
        String subjectName  = body.get("subjectName");
        log.info("POST /api/elective-enrollment: studentId={} class={} group={} subject={}",
                studentId, className, optionalGroup, subjectName);
        try {
            ElectiveEnrollmentDTO saved = service.enroll(studentId, className, optionalGroup, subjectName);
            return new ResponseEntity<>(saved, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{studentId}/{className}/{optionalGroup}")
    public ResponseEntity<?> unenroll(@PathVariable String studentId,
                                      @PathVariable String className,
                                      @PathVariable String optionalGroup) {
        log.info("DELETE /api/elective-enrollment/{}/{}/{}", studentId, className, optionalGroup);
        service.unenroll(studentId, className, optionalGroup);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bulk")
    public ResponseEntity<?> bulkEnroll(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> studentIds = (List<String>) body.get("studentIds");
        String className     = (String) body.get("className");
        String optionalGroup = (String) body.get("optionalGroup");
        String subjectName   = (String) body.get("subjectName");
        log.info("POST /api/elective-enrollment/bulk: class={} group={} subject={} count={}",
                className, optionalGroup, subjectName, studentIds == null ? 0 : studentIds.size());
        int count = service.bulkEnroll(studentIds, className, optionalGroup, subjectName);
        return ResponseEntity.ok(Map.of("enrolled", count));
    }
}
