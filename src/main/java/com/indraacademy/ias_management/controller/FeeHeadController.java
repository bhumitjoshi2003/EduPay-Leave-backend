package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.FeeHeadDto;
import com.indraacademy.ias_management.service.FeeHeadService;
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

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT', 'SUB_ADMIN')")
    public ResponseEntity<List<FeeHeadDto>> getActiveFeeHeads() {
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
}
