package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.HomeworkClassworkDtos.CreateRequest;
import com.indraacademy.ias_management.dto.HomeworkClassworkDtos.UpdateRequest;
import com.indraacademy.ias_management.dto.HomeworkClassworkDtos.WorkView;
import com.indraacademy.ias_management.service.HomeworkClassworkService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/homework-classwork")
public class HomeworkClassworkController {

    private final HomeworkClassworkService service;

    public HomeworkClassworkController(HomeworkClassworkService service) {
        this.service = service;
    }

    // ─── Teacher ────────────────────────────────────────────────────────

    @PreAuthorize("hasRole('TEACHER')")
    @PostMapping
    public WorkView create(@RequestBody CreateRequest request) {
        return service.create(request);
    }

    @PreAuthorize("hasRole('TEACHER')")
    @PutMapping("/{id}")
    public WorkView update(@PathVariable long id, @RequestBody UpdateRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize("hasRole('TEACHER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('TEACHER')")
    @GetMapping("/my")
    public List<WorkView> myPosts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.myPostsOn(date);
    }

    @PreAuthorize("hasRole('TEACHER')")
    @GetMapping("/my/recent")
    public List<WorkView> myRecent() {
        return service.myRecentPosts();
    }

    // ─── Student ────────────────────────────────────────────────────────

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/student")
    public List<WorkView> studentOn(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.studentOn(date);
    }

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/student/upcoming")
    public List<WorkView> studentUpcoming() {
        return service.studentUpcoming();
    }

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/student/recent")
    public List<WorkView> studentRecent() {
        return service.studentRecent();
    }

    // ─── Single post: owner teacher, student in that class, or school ADMIN/SUB_ADMIN (read-only) ──

    @PreAuthorize("hasAnyRole('TEACHER','STUDENT','ADMIN','SUB_ADMIN')")
    @GetMapping("/{id}")
    public WorkView get(@PathVariable long id) {
        return service.get(id);
    }
}
