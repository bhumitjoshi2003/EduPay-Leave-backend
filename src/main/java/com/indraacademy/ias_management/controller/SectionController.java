package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.SectionDTO;
import com.indraacademy.ias_management.service.SectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sections")
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
    public ResponseEntity<SectionDTO> createSection(@RequestBody SectionDTO dto) {
        return ResponseEntity.ok(sectionService.createSection(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SectionDTO> updateSection(@PathVariable Long id, @RequestBody SectionDTO dto) {
        return ResponseEntity.ok(sectionService.updateSection(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteSection(@PathVariable Long id) {
        sectionService.deleteSection(id);
        return ResponseEntity.ok().build();
    }
}
