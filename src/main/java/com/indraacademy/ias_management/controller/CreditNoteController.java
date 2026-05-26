package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.CreditNoteDto;
import com.indraacademy.ias_management.entity.CreditNoteStatus;
import com.indraacademy.ias_management.service.CreditNoteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/credit-notes")
public class CreditNoteController {

    @Autowired
    private CreditNoteService creditNoteService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CreditNoteDto> createCreditNote(
            @Valid @RequestBody CreditNoteDto dto, HttpServletRequest request) {
        return ResponseEntity.ok(creditNoteService.createCreditNote(dto, request));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CreditNoteDto> approveCreditNote(
            @PathVariable Long id, HttpServletRequest request) {
        return ResponseEntity.ok(creditNoteService.approveCreditNote(id, request));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<CreditNoteDto>> getCreditNotes(
            @RequestParam(required = false) String status,
            Pageable pageable) {
        CreditNoteStatus cnStatus = status != null ? CreditNoteStatus.valueOf(status) : null;
        return ResponseEntity.ok(creditNoteService.getCreditNotes(cnStatus, pageable));
    }
}
