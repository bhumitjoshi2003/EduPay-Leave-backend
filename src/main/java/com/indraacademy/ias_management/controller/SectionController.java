package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.SectionDTO;
import com.indraacademy.ias_management.service.SectionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import java.util.List;

@RestController
@RequestMapping("/api/sections")
@PreAuthorize("isAuthenticated()")
public class SectionController {

    @Autowired
    private SectionService sectionService;

    @GetMapping
    public ResponseEntity<List<SectionDTO>> getAllSections() {
        return ResponseEntity.ok(sectionService.getAllSectionsForSchool());
    }

    @GetMapping("/class/{classId}")
    public ResponseEntity<List<SectionDTO>> getSectionsForClass(@PathVariable Long classId) {
        return ResponseEntity.ok(sectionService.getSectionsForClass(classId));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SectionDTO> createSection(@Valid @RequestBody SectionDTO dto, HttpServletRequest request) {
        return ResponseEntity.ok(sectionService.createSection(dto, request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SectionDTO> updateSection(@PathVariable Long id, @Valid @RequestBody SectionDTO dto, HttpServletRequest request) {
        return ResponseEntity.ok(sectionService.updateSection(id, dto, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> deleteSection(@PathVariable Long id, HttpServletRequest request) {
        long affected = sectionService.deleteSection(id, request);
        return ResponseEntity.ok(Map.of("affected", affected));
    }
}
