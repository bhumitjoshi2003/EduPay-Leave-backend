package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.AssessmentDtos.AssessmentView;
import com.indraacademy.ias_management.dto.AssessmentDtos.ContextClass;
import com.indraacademy.ias_management.dto.AssessmentDtos.CreateRequest;
import com.indraacademy.ias_management.dto.AssessmentDtos.UpdateRequest;
import com.indraacademy.ias_management.service.AssessmentService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

/** SUB_ADMIN is intentionally absent: there is no assessment permission for it (fail closed). */
@RestController
@RequestMapping("/api/assessments")
public class AssessmentController {

    private final AssessmentService service;

    public AssessmentController(AssessmentService service) {
        this.service = service;
    }

    // ─── Teacher / school admin ─────────────────────────────────────────

    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    @GetMapping("/contexts")
    public List<ContextClass> contexts() {
        return service.contexts();
    }

    /** scope = upcoming (default) | past. */
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    @GetMapping("/manage")
    public List<AssessmentView> manage(@RequestParam(defaultValue = "upcoming") String scope) {
        return service.manage(scope);
    }

    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    @PostMapping
    public AssessmentView create(@RequestBody CreateRequest request) {
        return service.create(request);
    }

    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    @PutMapping("/{id}")
    public AssessmentView update(@PathVariable long id, @RequestBody UpdateRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ─── Student ────────────────────────────────────────────────────────

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/student/upcoming")
    public List<AssessmentView> studentUpcoming(@RequestParam(required = false) Integer limit) {
        return service.studentUpcoming(limit);
    }

    /** month = yyyy-MM (default: the school's current month). */
    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/student/month")
    public List<AssessmentView> studentMonth(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return service.studentMonth(month);
    }

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/student/past")
    public List<AssessmentView> studentPast() {
        return service.studentPast();
    }

    // ─── Single: creator teacher, school admin, or a student of that class ──

    @PreAuthorize("hasAnyRole('TEACHER','ADMIN','STUDENT')")
    @GetMapping("/{id}")
    public AssessmentView get(@PathVariable long id) {
        return service.get(id);
    }
}
