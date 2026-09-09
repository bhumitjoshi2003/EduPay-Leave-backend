package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/timetable/corrections")
public class TimetableCorrectionController {
    @Autowired private TimetableCorrectionService service;
    @Autowired private AuthService auth;
    public record Submit(@NotNull Long timetableEntryId, String requestedTeacherId, @Size(max=500) String reason) {}
    @GetMapping
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public List<TimetableCorrectionService.View> list() {
        return service.list(Role.TEACHER.equals(auth.getRole()) ? auth.getUserId() : null);
    }
    @PostMapping
    @PreAuthorize("hasRole('" + Role.TEACHER + "')")
    public ResponseEntity<?> submit(@Valid @RequestBody Submit body, HttpServletRequest http) {
        return ResponseEntity.status(201).body(service.submit(body.timetableEntryId(), auth.getUserId(), body.requestedTeacherId(), body.reason(), http));
    }
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public TimetableCorrectionService.View approve(@PathVariable Long id, HttpServletRequest http) { return service.review(id, true, http); }
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public TimetableCorrectionService.View reject(@PathVariable Long id, HttpServletRequest http) { return service.review(id, false, http); }
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<?> forbidden(SecurityException e) { return ResponseEntity.status(403).body(Map.of("message", e.getMessage())); }
    @ExceptionHandler({DataIntegrityViolationException.class, IllegalStateException.class, org.springframework.orm.ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<?> conflict(RuntimeException e) { return ResponseEntity.status(409).body(Map.of("code", "TIMETABLE_CORRECTION_CONFLICT", "message", e instanceof TimetableCorrectionConflict || e instanceof IllegalStateException ? e.getMessage() : "The timetable or request changed. Please refresh and try again.")); }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> missing(NoSuchElementException e) { return ResponseEntity.status(404).body(Map.of("message", e.getMessage())); }
}
