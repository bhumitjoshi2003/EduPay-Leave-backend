package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.FeePaymentDto;
import com.indraacademy.ias_management.dto.RecordPaymentRequest;
import com.indraacademy.ias_management.entity.PaymentStatus;
import com.indraacademy.ias_management.service.FeePaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/fee-payments")
public class FeePaymentController {

    @Autowired
    private FeePaymentService feePaymentService;

    /** Record a payment (online or manual) and allocate against invoices */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT')")
    public ResponseEntity<FeePaymentDto> recordPayment(
            @Valid @RequestBody RecordPaymentRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(feePaymentService.recordPayment(request, httpRequest));
    }

    /** Get single payment details */
    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT')")
    public ResponseEntity<FeePaymentDto> getPayment(@PathVariable Long paymentId) {
        return ResponseEntity.ok(feePaymentService.getPayment(paymentId));
    }

    /** Admin: paginated payment history with optional filters */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<FeePaymentDto>> getPaymentHistory(
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDateTime since,
            Pageable pageable) {
        PaymentStatus paymentStatus = status != null ? PaymentStatus.valueOf(status) : null;
        return ResponseEntity.ok(feePaymentService.getPaymentHistory(studentId, paymentStatus, since, pageable));
    }

    /** Student: own payment history */
    @GetMapping("/student/{studentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT')")
    public ResponseEntity<Page<FeePaymentDto>> getStudentPaymentHistory(
            @PathVariable String studentId, Pageable pageable) {
        return ResponseEntity.ok(feePaymentService.getStudentPaymentHistory(studentId, pageable));
    }
}
