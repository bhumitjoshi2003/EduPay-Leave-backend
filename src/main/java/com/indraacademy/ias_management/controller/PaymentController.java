package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.PaymentResponseDTO;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.PaymentService;
import com.indraacademy.ias_management.service.RazorpayService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/payments")
@CrossOrigin(origins = "http://localhost:4200")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    @Autowired private RazorpayService razorpayService;
    @Autowired private PaymentService paymentService;
    @Autowired private AuthService authService;

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody Map<String, Object> requestBody) {
        @SuppressWarnings("unchecked")
        Map<String, Object> paymentData = (Map<String, Object>) requestBody.get("paymentData");

        log.info("Request to create payment order for student: {}", paymentData.get("studentId"));

        try {
            // Robust parsing with proper casting and null checks
            int amount = (int) paymentData.get("totalAmount");
            String studentId = (String) paymentData.get("studentId");

            // Extracting fields robustly. Assumes the incoming data structure is mostly correct.
            String studentName = (String) paymentData.get("studentName");
            String className = (String) paymentData.get("className");
            String session = (String) paymentData.get("session");
            String month = (String) paymentData.get("monthSelectionString");
            int busFee = (int) paymentData.get("totalBusFee");
            int tuitionFee = (int) paymentData.get("totalTuitionFee");
            int annualCharges = (int) paymentData.get("totalAnnualCharges");
            int labCharges = (int) paymentData.get("totalLabCharges");
            int ecaProject = (int) paymentData.get("totalEcaProject");
            int examinationFee = (int) paymentData.get("totalExaminationFee");

            // Robustly handling optional/nullable integer fields
            int additionalCharges = ((Number) paymentData.getOrDefault("additionalCharges", 0)).intValue();
            int lateFees = ((Number) paymentData.getOrDefault("lateFees", 0)).intValue();

            Map<String, Object> order = razorpayService.createOrder(amount, studentId, studentName, className, session, month, busFee, tuitionFee, annualCharges, labCharges, ecaProject, examinationFee, additionalCharges, lateFees);
            log.info("Razorpay order created successfully for student {}.", studentId);
            return ResponseEntity.ok(order);
        } catch (ClassCastException | NullPointerException e) {
            log.error("Invalid data format in order creation request.", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid data format in payment request."));
        } catch (IllegalArgumentException e) {
            log.error("Payment order creation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error during Razorpay order creation.", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to create payment order."));
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyPayment(@RequestBody Map<String, Object> requestBody) {
        log.info("Request to verify payment.");
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> paymentData = (Map<String, String>) requestBody.get("paymentResponse");
            @SuppressWarnings("unchecked")
            Map<String, Object> orderDetails = (Map<String, Object>) requestBody.get("orderDetails");

            Map<String, Object> result = razorpayService.verifyPayment(paymentData, orderDetails);
            log.info("Payment verification result: {}", result.get("status"));
            return ResponseEntity.ok(result);
        } catch (ClassCastException | NullPointerException e) {
            log.error("Invalid data format in payment verification request.", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid data format in verification request."));
        } catch (IllegalArgumentException e) {
            log.error("Payment verification failed (Bad Request): {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error during payment verification.", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to verify payment."));
        }
    }

    @GetMapping("/history/students")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')") // Assuming this endpoint is for admin/staff to see all
    public ResponseEntity<Page<Payment>> getPaymentHistory(
            @RequestParam(required = false) String className,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false)  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDate,
            Pageable pageable
    ) {
        log.info("Request for payment history filtered. Class: {}, Student: {}, Date: {}", className, studentId, paymentDate);
        try {
            return ResponseEntity.ok(
                    paymentService.gePaymentHistoryFiltered(className, studentId, paymentDate, pageable)
            );
        } catch (IllegalArgumentException e) {
            log.error("Invalid filter parameters for payment history: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/history/student/{studentId}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "')")
    public ResponseEntity<Page<Payment>> getPaymentHistoryOfStudent(
            @PathVariable String studentId, Pageable pageable,
            @RequestHeader(name = "Authorization") String authorizationHeader){

        String authenticatedStudentId = authService.getUserIdFromToken(authorizationHeader);
        // Enforce security: student can only see their own history
        if (authService.getRoleFromToken(authorizationHeader).equals(Role.STUDENT) && !authenticatedStudentId.equals(studentId)) {
            log.warn("Security violation attempt: Student {} tried to access payment history for {}", authenticatedStudentId, studentId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.info("Request for payment history for student: {}", authenticatedStudentId);
        try {
            return ResponseEntity.ok(
                    paymentService.getPaymentHistoryByStudentId(authenticatedStudentId, pageable));
        } catch (IllegalArgumentException e) {
            log.error("Invalid student ID for payment history: {}", authenticatedStudentId);
            return ResponseEntity.badRequest().build();
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT +  "')")
    @GetMapping("/history/details/{paymentId}")
    public ResponseEntity<PaymentResponseDTO> getPaymentHistoryDetails(@PathVariable String paymentId) {
        log.info("Request for payment details for ID: {}", paymentId);
        try {
            PaymentResponseDTO dto = paymentService.getPaymentHistoryDetails(paymentId);
            if (dto == null) {
                log.warn("Payment details not found for ID: {}", paymentId);
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException e) {
            log.error("Invalid payment ID format: {}", paymentId);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/history/receipt/{paymentId}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT +  "')")
    public ResponseEntity<byte[]> downloadPaymentReceipt(@PathVariable String paymentId) {
        log.info("Request to download receipt for payment ID: {}", paymentId);

        byte[] pdfBytes;
        try {
            pdfBytes = paymentService.generatePaymentReceiptPdf(paymentId);
        } catch (NoSuchElementException e) {
            log.warn("Receipt not found for payment ID: {}", paymentId);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error generating PDF receipt for payment ID: {}", paymentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        if (pdfBytes == null || pdfBytes.length == 0) {
            log.warn("PDF generation returned empty content for payment ID: {}", paymentId);
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "receipt_" + paymentId + ".pdf");

        return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
    }
}