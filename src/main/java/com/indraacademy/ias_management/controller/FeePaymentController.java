package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.FeePaymentDto;
import com.indraacademy.ias_management.dto.RecordPaymentRequest;
import com.indraacademy.ias_management.entity.PaymentStatus;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.FeePaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/fee-payments")
public class FeePaymentController {

    @Autowired
    private FeePaymentService feePaymentService;

    @Autowired
    private AuthService authService;

    /** Record a payment (online or manual) and allocate against invoices. Students can only pay for themselves. */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT')")
    public ResponseEntity<?> recordPayment(
            @Valid @RequestBody RecordPaymentRequest request,
            HttpServletRequest httpRequest) {
        if (Role.STUDENT.equals(authService.getRole()) && !request.getStudentId().equals(authService.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Students can only record payments for themselves.");
        }
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

    /** Student: own payment history. Students can only view their own. */
    @GetMapping("/student/{studentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STUDENT')")
    public ResponseEntity<?> getStudentPaymentHistory(
            @PathVariable String studentId, Pageable pageable) {
        // STUDENT can only access their own payment history
        if (Role.STUDENT.equals(authService.getRole()) && !studentId.equals(authService.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Students can only view their own payment history.");
        }
        return ResponseEntity.ok(feePaymentService.getStudentPaymentHistory(studentId, pageable));
    }
}
