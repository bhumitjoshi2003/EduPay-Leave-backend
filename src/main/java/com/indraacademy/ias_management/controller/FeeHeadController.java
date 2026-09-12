package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.FeeHeadDto;
import com.indraacademy.ias_management.service.FeeHeadService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.ParentPortalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/fee-heads")
public class FeeHeadController {

    @Autowired
    private FeeHeadService feeHeadService;
    @Autowired private AuthService authService;
    @Autowired private ParentPortalService parentPortalService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT', 'SUB_ADMIN', 'PARENT')")
    public ResponseEntity<List<FeeHeadDto>> getActiveFeeHeads(
            @RequestParam(required = false) String studentId) {
        if ("PARENT".equals(authService.getRole())) {
            if (studentId == null || studentId.isBlank()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "A linked student is required");
            }
            parentPortalService.assertChildAccess(studentId, ParentPortalService.ChildPermission.FEES);
        }
        return ResponseEntity.ok(feeHeadService.getActiveFeeHeads());
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<FeeHeadDto>> getAllFeeHeads() {
        return ResponseEntity.ok(feeHeadService.getAllFeeHeads());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<FeeHeadDto> createFeeHead(
            @Valid @RequestBody FeeHeadDto dto, HttpServletRequest request) {
        return ResponseEntity.ok(feeHeadService.createFeeHead(dto, request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<FeeHeadDto> updateFeeHead(
            @PathVariable Long id,
            @Valid @RequestBody FeeHeadDto dto,
            HttpServletRequest request) {
        return ResponseEntity.ok(feeHeadService.updateFeeHead(id, dto, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteFeeHead(@PathVariable Long id, HttpServletRequest request) {
        feeHeadService.deleteFeeHead(id, request);
        return ResponseEntity.noContent().build();
    }
}
