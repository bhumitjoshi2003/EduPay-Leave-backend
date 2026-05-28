package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.AcademicSessionDto;
import com.indraacademy.ias_management.service.AcademicSessionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/academic-sessions")
public class AcademicSessionController {

    @Autowired
    private AcademicSessionService sessionService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER', 'STUDENT', 'SUB_ADMIN')")
    public ResponseEntity<List<AcademicSessionDto>> getAllSessions() {
        return ResponseEntity.ok(sessionService.getAllSessions());
    }

    @GetMapping("/current")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER', 'STUDENT', 'SUB_ADMIN')")
    public ResponseEntity<AcademicSessionDto> getCurrentSession() {
        return ResponseEntity.ok(sessionService.getCurrentSession());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AcademicSessionDto> createSession(@Valid @RequestBody AcademicSessionDto dto) {
        return ResponseEntity.ok(sessionService.createSession(dto));
    }

    @PutMapping("/{sessionId}/set-current")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AcademicSessionDto> setCurrentSession(@PathVariable Long sessionId) {
        return ResponseEntity.ok(sessionService.setCurrentSession(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteSession(@PathVariable Long sessionId) {
        sessionService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}
