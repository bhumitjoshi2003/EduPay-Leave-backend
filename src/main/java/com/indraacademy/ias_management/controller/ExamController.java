package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.ExamConfig;
import com.indraacademy.ias_management.entity.ExamSubjectEntry;
import com.indraacademy.ias_management.service.ExamConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/exams")
public class ExamController {

    private static final Logger log = LoggerFactory.getLogger(ExamController.class);

    @Autowired private ExamConfigService examConfigService;

    // ─── ExamConfig ───────────────────────────────────────────────────────────

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "', '" + Role.TEACHER + "')")
    @GetMapping
    public ResponseEntity<List<ExamConfig>> getExams(
            @RequestParam(required = false) String session,
            @RequestParam(required = false) String className) {
        log.info("GET /api/exams?session={}&className={}", session, className);
        return ResponseEntity.ok(examConfigService.getExams(session, className));
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PostMapping
    public ResponseEntity<?> addExam(@RequestBody Map<String, String> body) {
        String session   = body.get("session");
        String className = body.get("className");
        String examName  = body.get("examName");
        log.info("POST /api/exams: session={}, className={}, examName={}", session, className, examName);
        ExamConfig saved = examConfigService.addExam(session, className, examName);
        return new ResponseEntity<>(saved, HttpStatus.CREATED);
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteExam(@PathVariable Long id) {
        log.info("DELETE /api/exams/{}", id);
        examConfigService.deleteExam(id);
        return ResponseEntity.noContent().build();
    }

    // ─── ExamSubjectEntry ─────────────────────────────────────────────────────

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "', '" + Role.TEACHER + "')")
    @GetMapping("/{examId}/subjects")
    public ResponseEntity<?> getExamSubjects(@PathVariable Long examId) {
        log.info("GET /api/exams/{}/subjects", examId);
        try {
            return ResponseEntity.ok(examConfigService.getExamSubjects(examId));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PostMapping("/{examId}/subjects")
    public ResponseEntity<?> addExamSubject(@PathVariable Long examId,
                                            @RequestBody Map<String, Object> body) {
        String subjectName = (String) body.get("subjectName");
        Integer maxMarks   = body.get("maxMarks") != null
                ? Integer.valueOf(body.get("maxMarks").toString()) : null;
        LocalDate examDate = body.get("examDate") != null
                ? LocalDate.parse(body.get("examDate").toString()) : null;
        log.info("POST /api/exams/{}/subjects: subject={}, maxMarks={}", examId, subjectName, maxMarks);
        try {
            ExamSubjectEntry saved = examConfigService.addExamSubject(examId, subjectName, maxMarks, examDate);
            return new ResponseEntity<>(saved, HttpStatus.CREATED);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PutMapping("/subjects/{entryId}")
    public ResponseEntity<?> updateExamSubject(@PathVariable Long entryId,
                                               @RequestBody Map<String, Object> body) {
        Integer maxMarks = body.get("maxMarks") != null
                ? Integer.valueOf(body.get("maxMarks").toString()) : null;
        LocalDate examDate = body.get("examDate") != null
                ? LocalDate.parse(body.get("examDate").toString()) : null;
        log.info("PUT /api/exams/subjects/{}: maxMarks={}, examDate={}", entryId, maxMarks, examDate);
        try {
            ExamSubjectEntry updated = examConfigService.updateExamSubject(entryId, maxMarks, examDate);
            return ResponseEntity.ok(updated);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @DeleteMapping("/subjects/{entryId}")
    public ResponseEntity<?> deleteExamSubject(@PathVariable Long entryId) {
        log.info("DELETE /api/exams/subjects/{}", entryId);
        try {
            examConfigService.deleteExamSubject(entryId);
            return ResponseEntity.noContent().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }
}
