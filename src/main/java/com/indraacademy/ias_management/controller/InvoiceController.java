package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.InvoiceDto;
import com.indraacademy.ias_management.dto.InvoiceGenerationRequest;
import com.indraacademy.ias_management.dto.StudentFeeOverviewDto;
import com.indraacademy.ias_management.entity.InvoiceStatus;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.InvoiceGenerationService;
import com.indraacademy.ias_management.service.InvoiceService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private InvoiceGenerationService invoiceGenerationService;

    @Autowired
    private AuthService authService;

    /** Admin generates invoices for a billing month */
    @PostMapping("/generate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> generateInvoices(
            @Valid @RequestBody InvoiceGenerationRequest request) {
        int count = invoiceGenerationService.generateInvoices(
                request.getAcademicSessionId(),
                request.getBillingMonth(),
                request.getClassName(),
                request.getStudentId());
        return ResponseEntity.ok(Map.of("generated", count));
    }

    /** Admin issues (finalizes) DRAFT invoices */
    @PostMapping("/issue")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> issueInvoices(
            @RequestParam Long sessionId,
            @RequestParam(required = false) Integer billingMonth) {
        int count = invoiceGenerationService.issueInvoices(sessionId, billingMonth);
        return ResponseEntity.ok(Map.of("issued", count));
    }

    /** Get single invoice with line items */
    @GetMapping("/{invoiceId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT')")
    public ResponseEntity<InvoiceDto> getInvoice(@PathVariable Long invoiceId) {
        return ResponseEntity.ok(invoiceService.getInvoice(invoiceId));
    }

    /** Student fee overview for a session. Students can only view their own. */
    @GetMapping("/student/{studentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT')")
    public ResponseEntity<?> getStudentFeeOverview(
            @PathVariable String studentId,
            @RequestParam(required = false) Long sessionId) {
        if (Role.STUDENT.equals(authService.getRole()) && !studentId.equals(authService.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Students can only view their own fee overview.");
        }
        return ResponseEntity.ok(invoiceService.getStudentFeeOverview(studentId, sessionId));
    }

    /** Outstanding (unpaid) invoices for a student — used by payment flow. Students can only view their own. */
    @GetMapping("/student/{studentId}/outstanding")
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT')")
    public ResponseEntity<?> getOutstandingInvoices(@PathVariable String studentId) {
        if (Role.STUDENT.equals(authService.getRole()) && !studentId.equals(authService.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Students can only view their own invoices.");
        }
        return ResponseEntity.ok(invoiceService.getOutstandingInvoices(studentId));
    }

    /** Admin paginated invoice list with filters */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<InvoiceDto>> getInvoices(
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String status,
            Pageable pageable) {
        InvoiceStatus invoiceStatus = status != null ? InvoiceStatus.valueOf(status) : null;
        return ResponseEntity.ok(invoiceService.getFilteredInvoices(studentId, sessionId, invoiceStatus, pageable));
    }
}
