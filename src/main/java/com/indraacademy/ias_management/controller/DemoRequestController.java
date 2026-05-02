package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.DemoRequestDTO;
import com.indraacademy.ias_management.dto.DemoRequestResponseDTO;
import com.indraacademy.ias_management.dto.DemoStatusUpdateDTO;
import com.indraacademy.ias_management.service.DemoRequestService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/demo-requests")
public class DemoRequestController {

    @Autowired
    private DemoRequestService demoRequestService;

    @PostMapping
    public ResponseEntity<String> submitDemoRequest(@Valid @RequestBody DemoRequestDTO dto) {
        demoRequestService.save(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body("Demo request received successfully.");
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<DemoRequestResponseDTO>> getAllDemoRequests() {
        return ResponseEntity.ok(demoRequestService.getAll());
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<DemoRequestResponseDTO> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody DemoStatusUpdateDTO dto) {
        return ResponseEntity.ok(demoRequestService.updateStatus(id, dto.getStatus()));
    }
}
