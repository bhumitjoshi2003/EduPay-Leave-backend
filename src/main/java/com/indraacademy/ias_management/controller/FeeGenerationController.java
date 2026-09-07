package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.FeeGenerationTargetDtos.*;
import com.indraacademy.ias_management.service.FeeGenerationTargetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

/** E5B: admin-triggered fee generation for a target AcademicSession, driven by authoritative
 *  StudentEnrollment rows. Separate workflow from year-end promotion and from the legacy
 *  per-class fee-assignment workflow in FeeWorkflowController. */
@RestController
@RequestMapping("/api/fee-generation")
@PreAuthorize("hasRole('" + Role.ADMIN + "')")
public class FeeGenerationController {
    private final FeeGenerationTargetService service;
    public FeeGenerationController(FeeGenerationTargetService service) { this.service = service; }

    @GetMapping("/target-preview")
    public ResponseEntity<?> preview(@RequestParam Long targetSessionId,
                                      @RequestParam(required = false) Long classId,
                                      @RequestParam(required = false) String studentId) {
        return handle(() -> service.preview(targetSessionId, classId, studentId));
    }

    @GetMapping("/target-drift")
    public ResponseEntity<?> targetDrift(@RequestParam Long targetSessionId,
                                         @RequestParam(required = false) Long classId,
                                         @RequestParam(required = false) String studentId) {
        return handle(() -> service.targetDrift(targetSessionId, classId, studentId));
    }

    @PostMapping("/target-generate")
    public ResponseEntity<?> generate(@Valid @RequestBody GenerationRequest request, HttpServletRequest http) {
        return handle(() -> service.generate(request, http.getRemoteAddr()));
    }

    private ResponseEntity<?> handle(Action action) {
        try { return ResponseEntity.ok(action.run()); }
        catch (NoSuchElementException ex) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage()); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }
    @FunctionalInterface private interface Action { Object run(); }
}
