package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.StudentStreamDTO;
import com.indraacademy.ias_management.entity.StudentStreamSelection;
import com.indraacademy.ias_management.service.StudentStreamService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/student-stream")
@CrossOrigin(origins = "http://localhost:4200")
@PreAuthorize("hasRole('" + Role.ADMIN + "')")
public class StudentStreamController {

    private static final Logger log = LoggerFactory.getLogger(StudentStreamController.class);

    @Autowired private StudentStreamService studentStreamService;

    @GetMapping("/{studentId}")
    public ResponseEntity<?> getSelection(@PathVariable String studentId) {
        log.info("GET /api/student-stream/{}", studentId);
        return studentStreamService.getSelection(studentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> assign(@RequestBody Map<String, Object> body) {
        String studentId         = (String) body.get("studentId");
        Long streamId            = body.get("streamId") != null ? Long.valueOf(body.get("streamId").toString()) : null;
        Long optionalSubjectId   = body.get("optionalSubjectId") != null
                ? Long.valueOf(body.get("optionalSubjectId").toString()) : null;
        log.info("POST /api/student-stream: studentId={}, streamId={}", studentId, streamId);
        StudentStreamSelection saved = studentStreamService.save(studentId, streamId, optionalSubjectId);
        return new ResponseEntity<>(saved, HttpStatus.CREATED);
    }

    @PutMapping("/{studentId}")
    public ResponseEntity<?> update(@PathVariable String studentId,
                                    @RequestBody Map<String, Object> body) {
        Long streamId          = body.get("streamId") != null ? Long.valueOf(body.get("streamId").toString()) : null;
        Long optionalSubjectId = body.get("optionalSubjectId") != null
                ? Long.valueOf(body.get("optionalSubjectId").toString()) : null;
        log.info("PUT /api/student-stream/{}: streamId={}", studentId, streamId);
        StudentStreamSelection updated = studentStreamService.update(studentId, streamId, optionalSubjectId);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{studentId}")
    public ResponseEntity<?> delete(@PathVariable String studentId) {
        log.info("DELETE /api/student-stream/{}", studentId);
        studentStreamService.delete(studentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/class/{className}")
    public ResponseEntity<List<StudentStreamDTO>> getClassSelections(@PathVariable String className) {
        log.info("GET /api/student-stream/class/{}", className);
        return ResponseEntity.ok(studentStreamService.getClassSelections(className));
    }

    @GetMapping("/eligible-students")
    public ResponseEntity<Map<String, Object>> getEligibleStudents() {
        log.info("GET /api/student-stream/eligible-students");
        var result = studentStreamService.getStreamEligibleStudents();
        return ResponseEntity.ok(Map.of(
                "eligibleClassCount", result.eligibleClassCount(),
                "students", result.students()
        ));
    }
}
