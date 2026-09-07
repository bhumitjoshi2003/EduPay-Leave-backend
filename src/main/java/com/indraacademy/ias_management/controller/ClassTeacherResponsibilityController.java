package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.CopyRequest;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.Request;
import com.indraacademy.ias_management.service.ClassTeacherActivationService;
import com.indraacademy.ias_management.service.ClassTeacherResponsibilityCopyService;
import com.indraacademy.ias_management.service.ClassTeacherResponsibilityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

/**
 * Phase F4. ADMIN/SUPER_ADMIN only throughout — this is configuration management and an explicit
 * authorization-affecting action (activation/apply), never a TEACHER-facing endpoint.
 */
@RestController
@RequestMapping("/api/class-teacher-responsibilities")
@PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
public class ClassTeacherResponsibilityController {

    private static final Logger log = LoggerFactory.getLogger(ClassTeacherResponsibilityController.class);

    @Autowired private ClassTeacherResponsibilityService responsibilityService;
    @Autowired private ClassTeacherResponsibilityCopyService copyService;
    @Autowired private ClassTeacherActivationService activationService;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam Long academicSessionId) {
        try {
            return ResponseEntity.ok(responsibilityService.list(academicSessionId));
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody Request req, HttpServletRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(responsibilityService.create(req, request));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody Request req, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(responsibilityService.update(id, req, request));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestParam Long academicSessionId, HttpServletRequest request) {
        log.warn("DELETE class-teacher-responsibility/{}, academicSessionId={}", id, academicSessionId);
        try {
            responsibilityService.delete(id, academicSessionId, request);
            return ResponseEntity.noContent().build();
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    @PostMapping("/copy-session")
    public ResponseEntity<?> copySession(@Valid @RequestBody CopyRequest req, HttpServletRequest request) {
        log.warn("POST class-teacher-responsibilities/copy-session: source={}, target={}",
                req.sourceAcademicSessionId(), req.targetAcademicSessionId());
        try {
            return ResponseEntity.ok(copyService.copy(req.sourceAcademicSessionId(), req.targetAcademicSessionId(), request));
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    /** Read-only preview of what applying the CURRENT session's configuration would do. Never
     *  writes. Doubles as the "is the live projection in sync with the current session" signal —
     *  see {@code ActivationPreviewResult#inSync}. */
    @GetMapping("/activation/preview")
    public ResponseEntity<?> activationPreview() {
        try {
            return ResponseEntity.ok(activationService.preview());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    /** Explicit apply — the only path that ever writes Teacher.classTeacher/classTeacherSectionId
     *  from configured responsibility data. Always targets the current session; never inferred,
     *  never automatic. */
    @PostMapping("/activation/apply")
    public ResponseEntity<?> activationApply(HttpServletRequest request) {
        log.warn("POST class-teacher-responsibilities/activation/apply");
        try {
            return ResponseEntity.ok(activationService.apply(request));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }
}
