package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.TeacherAdoptionMetadataRequest;
import com.indraacademy.ias_management.service.TeacherAdoptionMetadataService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/adoption")
@PreAuthorize("hasRole('TEACHER')")
public class TeacherAdoptionMetadataController {
    private final TeacherAdoptionMetadataService service;

    public TeacherAdoptionMetadataController(TeacherAdoptionMetadataService service) { this.service = service; }

    @PostMapping("/android-version")
    public ResponseEntity<Void> reportAndroidVersion(@Valid @RequestBody TeacherAdoptionMetadataRequest request) {
        service.reportAndroidVersion(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/onboarding-completed")
    public ResponseEntity<Void> completeOnboarding() {
        service.completeOnboarding();
        return ResponseEntity.noContent().build();
    }
}
