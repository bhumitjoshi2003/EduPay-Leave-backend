package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.ClassUpdateDtos.CreateRequest;
import com.indraacademy.ias_management.dto.ClassUpdateDtos.TeachingContext;
import com.indraacademy.ias_management.dto.ClassUpdateDtos.UpdateRequest;
import com.indraacademy.ias_management.dto.ClassUpdateDtos.UpdateView;
import com.indraacademy.ias_management.service.ClassUpdateService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/class-updates")
public class ClassUpdateController {

    private final ClassUpdateService service;

    public ClassUpdateController(ClassUpdateService service) {
        this.service = service;
    }

    // ─── Teacher ────────────────────────────────────────────────────────

    @PreAuthorize("hasRole('TEACHER')")
    @GetMapping("/my/contexts")
    public List<TeachingContext> myContexts() {
        return service.myContexts();
    }

    @PreAuthorize("hasRole('TEACHER')")
    @GetMapping("/my")
    public List<UpdateView> myRecent() {
        return service.myRecent();
    }

    @PreAuthorize("hasRole('TEACHER')")
    @PostMapping
    public UpdateView create(@RequestBody CreateRequest request) {
        return service.create(request);
    }

    @PreAuthorize("hasRole('TEACHER')")
    @PutMapping("/{id}")
    public UpdateView update(@PathVariable long id, @RequestBody UpdateRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize("hasRole('TEACHER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ─── Student ────────────────────────────────────────────────────────

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/student")
    public List<UpdateView> studentActive(@RequestParam(required = false) Integer limit) {
        return service.studentActive(limit);
    }

    // ─── Single update: owner teacher, student in that class, or school ADMIN/SUB_ADMIN (read-only) ──

    @PreAuthorize("hasAnyRole('TEACHER','STUDENT','ADMIN','SUB_ADMIN')")
    @GetMapping("/{id}")
    public UpdateView get(@PathVariable long id) {
        return service.get(id);
    }
}
