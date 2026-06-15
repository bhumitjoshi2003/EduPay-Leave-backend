package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.CreateOrderRequest;
import com.indraacademy.ias_management.dto.PaymentResponseDTO;
import com.indraacademy.ias_management.dto.RefundRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.service.AuditService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.PaymentService;
import com.indraacademy.ias_management.service.RazorpayService;
import com.indraacademy.ias_management.util.SecurityUtil;
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

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    @Autowired private RazorpayService razorpayService;
    @Autowired private PaymentService paymentService;
    @Autowired private AuthService authService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;

    @PostMapping("/create")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "')")
    public ResponseEntity<Map<String, Object>> createOrder(@Valid @RequestBody CreateOrderRequest req) {
        log.info("Request to create payment order for student: {}", req.getStudentId());

        // Server-side fee amount validation: verify client-submitted amount against outstanding balance
        int clientAmount = req.getTotalAmount(); // in paise
        if (clientAmount <= 0) {
            log.warn("Rejected order creation: amount must be positive. Received: {} for student: {}", clientAmount, req.getStudentId());
            return ResponseEntity.badRequest().body(Map.of("error", "Payment amount must be greater than zero."));
        }

        long totalOutstandingPaise = razorpayService.calculateOutstandingBalancePaise(
                req.getStudentId(), req.getSession());
        if (totalOutstandingPaise <= 0) {
            log.warn("Rejected order creation: no outstanding fees for student: {} session: {}", req.getStudentId(), req.getSession());
            return ResponseEntity.badRequest().body(Map.of("error", "No outstanding fees found for this student and session."));
        }

        if (clientAmount > totalOutstandingPaise) {
            log.warn("Rejected order creation: client amount {} exceeds outstanding balance {} for student: {}",
                    clientAmount, totalOutstandingPaise, req.getStudentId());
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Payment amount exceeds the total outstanding balance. Outstanding: " + totalOutstandingPaise + " paise."));
        }

        // Log a warning if amounts differ (partial payment is allowed, but full mismatch is suspicious)
        long componentSum = (long) req.getTotalTuitionFee() + req.getTotalBusFee()
                + req.getTotalAnnualCharges() + req.getTotalLabCharges()
                + req.getTotalEcaProject() + req.getTotalExaminationFee()
                + req.getAdditionalCharges() + req.getLateFees() + req.getPlatformFee();
        if (clientAmount != componentSum) {
            log.warn("Amount component mismatch for student {}: totalAmount={} but component sum={}",
                    req.getStudentId(), clientAmount, componentSum);
        }

        Map<String, Object> order = razorpayService.createOrder(
                req.getTotalAmount(),
                req.getStudentId(),
                req.getStudentName(),
                req.getClassName(),
                req.getSession(),
                req.getMonthSelectionString(),
                req.getTotalBusFee(),
                req.getTotalTuitionFee(),
                req.getTotalAnnualCharges(),
                req.getTotalLabCharges(),
                req.getTotalEcaProject(),
                req.getTotalExaminationFee(),
                req.getAdditionalCharges(),
                req.getLateFees(),
                req.getPlatformFee()
        );
        log.info("Razorpay order created successfully for student {}.", req.getStudentId());
        return ResponseEntity.ok(order);
    }

    @PostMapping("/verify")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "')")
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
        }
    }

    @GetMapping("/history/students")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    public ResponseEntity<Page<Payment>> getPaymentHistory(
            @RequestParam(required = false) String className,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false)  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDate,
            Pageable pageable
    ) {
        log.info("Request for payment history filtered. Class: {}, Student: {}, Date: {}", className, studentId, paymentDate);
        return ResponseEntity.ok(
                paymentService.gePaymentHistoryFiltered(className, studentId, paymentDate, pageable)
        );
    }

    @GetMapping("/history/student/{studentId}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "')")
    public ResponseEntity<Page<Payment>> getPaymentHistoryOfStudent(
            @PathVariable String studentId, Pageable pageable){

        log.info("Request for payment history for student: {}", studentId);
        return ResponseEntity.ok(
                paymentService.getPaymentHistoryByStudentId(studentId, pageable));
    }

    @GetMapping("/history/details/{paymentId}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "')")
    public ResponseEntity<PaymentResponseDTO> getPaymentHistoryDetails(@PathVariable String paymentId) {
        log.info("Request for payment details for ID: {}", paymentId);
        PaymentResponseDTO dto = paymentService.getPaymentHistoryDetails(paymentId);
        if (dto == null) {
            log.warn("Payment details not found for ID: {}", paymentId);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/history/receipt/{paymentId}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "')")
    public ResponseEntity<byte[]> downloadPaymentReceipt(@PathVariable String paymentId) {
        log.info("Request to download receipt for payment ID: {}", paymentId);

        byte[] pdfBytes = paymentService.generatePaymentReceiptPdf(paymentId);

        if (pdfBytes == null || pdfBytes.length == 0) {
            log.warn("PDF generation returned empty content for payment ID: {}", paymentId);
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "receipt_" + paymentId + ".pdf");

        return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
    }

    @PostMapping("/{paymentId}/refund")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<Map<String, Object>> refundPayment(
            @PathVariable Long paymentId,
            @Valid @RequestBody RefundRequest request,
            HttpServletRequest httpRequest) {

        log.info("Refund request for payment ID: {} amount: {} paise", paymentId, request.getAmount());

        Long schoolId = securityUtil.getSchoolId();

        // 1. Look up payment by ID and schoolId
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || !schoolId.equals(payment.getSchoolId())) {
            log.warn("Payment not found or does not belong to school. paymentId={} schoolId={}", paymentId, schoolId);
            return ResponseEntity.notFound().build();
        }

        // 2. Validate refund amount <= payment amount (both in paise after Task 3 fix)
        if (request.getAmount() > payment.getAmountPaid()) {
            log.warn("Refund amount {} exceeds payment amount {} for paymentId={}",
                    request.getAmount(), payment.getAmountPaid(), paymentId);
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Refund amount exceeds the original payment amount."));
        }

        // 3. Call Razorpay refund API
        String razorpayPaymentId = payment.getPaymentId();
        if (razorpayPaymentId == null || razorpayPaymentId.isBlank()) {
            log.error("No Razorpay payment ID found for payment record {}", paymentId);
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Cannot refund: no Razorpay payment ID associated with this record."));
        }

        try {
            Map<String, Object> refundResult = razorpayService.createRefund(
                    razorpayPaymentId, request.getAmount(), request.getReason());

            // 4. Update payment record status
            payment.setStatus("refunded");
            paymentRepository.save(payment);

            // 5. Audit log the refund
            String ip = httpRequest.getRemoteAddr();
            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "REFUND_PAYMENT",
                    "Payment",
                    paymentId.toString(),
                    null,
                    String.format("Refund of %d paise. Reason: %s. RefundId: %s",
                            request.getAmount(), request.getReason(), refundResult.get("refundId")),
                    ip
            );

            log.info("Refund processed successfully for paymentId={}", paymentId);
            return ResponseEntity.ok(refundResult);

        } catch (RuntimeException e) {
            log.error("Refund failed for paymentId={}", paymentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Refund failed: " + e.getMessage()));
        }
    }
}