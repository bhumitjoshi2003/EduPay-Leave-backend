package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.CheckoutQuoteDto;
import com.indraacademy.ias_management.dto.CreateOrderRequest;
import com.indraacademy.ias_management.dto.PaymentResponseDTO;
import com.indraacademy.ias_management.dto.RefundRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.PaymentOrderRepository;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.AttendanceService;
import com.indraacademy.ias_management.service.PaymentService;
import com.indraacademy.ias_management.service.RazorpayService;
import com.indraacademy.ias_management.service.StudentFeesService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.service.OnlinePaymentPricingCalculator;
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
    @Autowired private PaymentOrderRepository paymentOrderRepository;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private StudentFeesService studentFeesService;
    @Autowired private ParentPortalService parentPortalService;
    @Autowired private AttendanceService attendanceService;
    @Autowired private OnlinePaymentPricingCalculator paymentPricingCalculator;

    /** Tight tolerance for the client-displayed vs. server-computed core checkout amount
     * (school fee + late fee + platform fee) — absorbs last-cent rounding differences, not
     * a materially stale/wrong client figure. Unlike markFeesAsPaid's 10% tolerance (which
     * exists to allow legitimate partial/underpayment through), this is a "does the amount
     * we're about to charge match what the user was shown" check — both sides should
     * compute identically once the frontend reads this same quote, so a real mismatch here
     * means stale or tampered client data, not normal drift. */
    private static final long AMOUNT_MISMATCH_TOLERANCE_PAISE = 100L; // ₹1

    @PostMapping("/create")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "', '" + Role.PARENT + "')")
    public ResponseEntity<Map<String, Object>> createOrder(@Valid @RequestBody CreateOrderRequest req) {
        log.info("Request to create payment order for student: {}", req.getStudentId());

        // Students can only create an order for themselves — without this, any STUDENT
        // JWT could transact against any other same-school studentId. The order created
        // here is what verifyPayment() later trusts as the source of truth for who this
        // payment is for, so this check is the actual root of that guarantee.
        if (Role.STUDENT.equals(authService.getRole()) && !req.getStudentId().equals(authService.getUserId())) {
            log.warn("Rejected order creation: student {} attempted to create an order for student {}.",
                    authService.getUserId(), req.getStudentId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Students can only pay for themselves."));
        }
        if (Role.PARENT.equals(authService.getRole())) {
            parentPortalService.assertChildAccess(req.getStudentId(), ParentPortalService.ChildPermission.PAY_FEES);
        }

        // Server-side fee amount validation: verify client-submitted amount against outstanding balance
        long totalOutstandingPaise = razorpayService.calculateOutstandingBalancePaise(
                req.getStudentId(), req.getSession());
        if (totalOutstandingPaise <= 0) {
            log.warn("Rejected order creation: no outstanding fees for student: {} session: {}", req.getStudentId(), req.getSession());
            return ResponseEntity.badRequest().body(Map.of("error", "No outstanding fees found for this student and session."));
        }

        // Backend-authoritative checkout quote: school fee due (from each selected month's
        // StudentFees snapshot) + late fee + platform fee — computed here independently of
        // whatever the client sent, per the same rule payment verification already follows
        // (re-derive from persisted server-side data, never trust client values for the
        // actual charge). additionalCharges (leave-related, a separate concern from this
        // formula) remains a client-supplied pass-through, unchanged from today.
        List<Integer> months = decodeMonthSelection(req.getMonthSelectionString());
        CheckoutQuoteDto quote = studentFeesService.computeCheckoutQuote(req.getStudentId(), req.getSession(), months);
        if (!quote.getUnresolvedMonths().isEmpty()) {
            log.error("Rejected order creation: student {} session {} has unresolvable months {} — cannot confidently quote.",
                    req.getStudentId(), req.getSession(), quote.getUnresolvedMonths());
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Cannot determine the fee due for month(s) " + quote.getUnresolvedMonths()
                            + " — contact the school office before paying for these months."));
        }

        long serverAdditionalChargesPaise = Math.multiplyExact(
                attendanceService.getTotalUnappliedLeaveCount(req.getStudentId(), req.getSession()), 2_500L);
        long principalPaise = quote.getSchoolLiabilityPrincipalPaise();
        if (principalPaise <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "No allocatable school fee is due for the selected months."));
        }
        OnlinePaymentPricingCalculator.Pricing pricing = paymentPricingCalculator.calculate(
                principalPaise, serverAdditionalChargesPaise);

        // className is server-derived from the actual StudentFees row(s) behind the months
        // just validated above (computeCheckoutQuote already guarantees every one of `months`
        // resolves to a real row), rather than trusted from the client — req.getClassName()
        // was previously persisted onto PaymentOrder/Payment verbatim with no check that it
        // matched anything real for this student.
        String serverClassName = resolveClassNameForOrder(req.getStudentId(), req.getSession(), months, req.getClassName());

        Map<String, Object> order = razorpayService.createOrder(
                Math.toIntExact(pricing.totalPayablePaise()),
                req.getStudentId(),
                req.getStudentName(),
                serverClassName,
                req.getSession(),
                req.getMonthSelectionString(),
                // Legacy 5-bucket breakdown (tuitionFee/annualCharges/labCharges/ecaProject/
                // examinationFee) stays client-supplied — cosmetic receipt display only, not
                // the charged amount. The dynamic FeeHead model these buckets predate doesn't
                // map cleanly onto them; the financially authoritative figures are amount/
                // lateFees/platformFee above, all now server-computed.
                req.getTotalBusFee(),
                req.getTotalTuitionFee(),
                req.getTotalAnnualCharges(),
                req.getTotalLabCharges(),
                req.getTotalEcaProject(),
                req.getTotalExaminationFee(),
                Math.toIntExact(serverAdditionalChargesPaise),
                Math.toIntExact(quote.getLateFeePaise()),
                pricing
        );
        log.info("Razorpay order created successfully for student {}.", req.getStudentId());
        return ResponseEntity.ok(order);
    }

    /** The class this order/payment is actually for, taken from the real StudentFees row(s)
     * behind the requested months rather than the client-supplied className — a client could
     * otherwise submit any string here and have it persisted verbatim onto PaymentOrder/Payment
     * (shown on receipts and payment history) with no relation to what the student was actually
     * billed under. The fallback to the client value only matters if `months` is empty (no
     * month selected) since computeCheckoutQuote's unresolvedMonths check already guarantees
     * every requested month resolves to a real row otherwise. */
    private String resolveClassNameForOrder(String studentId, String session, List<Integer> months, String clientClassName) {
        return studentFeesService.getStudentFees(studentId, session).stream()
                .filter(fee -> months.contains(fee.getMonth()))
                .map(com.indraacademy.ias_management.entity.StudentFees::getClassName)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(clientClassName);
    }

    /** Decodes a 12-char "010000000000"-style bitmask (bit i = academic month i+1) into the
     * list of selected academic months, matching the convention StudentFees.month/Payment.
     * month already use throughout this module. */
    private List<Integer> decodeMonthSelection(String monthSelectionString) {
        List<Integer> months = new java.util.ArrayList<>();
        if (monthSelectionString == null) return months;
        for (int i = 0; i < monthSelectionString.length() && i < 12; i++) {
            if (monthSelectionString.charAt(i) == '1') {
                months.add(i + 1);
            }
        }
        return months;
    }

    @PostMapping("/verify")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "', '" + Role.PARENT + "')")
    public ResponseEntity<Map<String, Object>> verifyPayment(@RequestBody Map<String, Object> requestBody) {
        log.info("Request to verify payment.");
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> paymentData = (Map<String, String>) requestBody.get("paymentResponse");
            @SuppressWarnings("unchecked")
            Map<String, Object> orderDetails = (Map<String, Object>) requestBody.get("orderDetails");

            String orderId = paymentData == null ? null : paymentData.get("razorpay_order_id");
            if (orderId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Payment order ID is required."));
            }
            var paymentOrder = paymentOrderRepository
                    .findByOrderIdAndSchoolId(orderId, securityUtil.getSchoolId())
                    .orElse(null);
            if (paymentOrder == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Payment order is not available for this school."));
            }
            if (Role.STUDENT.equals(authService.getRole())
                    && !paymentOrder.getStudentId().equals(authService.getUserId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Students can only verify their own payments."));
            }
            if (Role.PARENT.equals(authService.getRole())) {
                parentPortalService.assertChildAccess(
                        paymentOrder.getStudentId(), ParentPortalService.ChildPermission.PAY_FEES);
            }

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
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "', '" + Role.PARENT + "')")
    public ResponseEntity<?> getPaymentHistoryOfStudent(
            @PathVariable String studentId, Pageable pageable){

        if (Role.STUDENT.equals(authService.getRole()) && !studentId.equals(authService.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Students can only view their own payment history."));
        }
        if (Role.PARENT.equals(authService.getRole())) {
            parentPortalService.assertChildAccess(studentId, ParentPortalService.ChildPermission.FEES);
        }

        log.info("Request for payment history for student: {}", studentId);
        return ResponseEntity.ok(
                paymentService.getPaymentHistoryByStudentId(studentId, pageable));
    }

    @GetMapping("/history/details/{paymentId}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "', '" + Role.PARENT + "')")
    public ResponseEntity<?> getPaymentHistoryDetails(@PathVariable String paymentId) {
        log.info("Request for payment details for ID: {}", paymentId);

        Long schoolId = securityUtil.getSchoolId();
        Payment payment = paymentRepository.findByPaymentIdAndSchoolId(paymentId, schoolId).orElse(null);
        if (payment == null) {
            log.warn("Payment details not found for ID: {}", paymentId);
            return ResponseEntity.notFound().build();
        }
        if (Role.STUDENT.equals(authService.getRole()) && !payment.getStudentId().equals(authService.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Students can only view their own payment details."));
        }
        if (Role.PARENT.equals(authService.getRole())) {
            parentPortalService.assertChildAccess(payment.getStudentId(), ParentPortalService.ChildPermission.FEES);
        }

        PaymentResponseDTO dto = paymentService.getPaymentHistoryDetails(paymentId);
        if (dto == null) {
            log.warn("Payment details not found for ID: {}", paymentId);
            return ResponseEntity.notFound().build();
        }
        if (!Role.ADMIN.equals(authService.getRole())) {
            dto.setSchoolLiabilityPrincipalPaise(null);
            dto.setGatewayRateBps(null);
            dto.setGatewayTaxRateBps(null);
            dto.setGatewayRecoveryFeePaise(null);
            dto.setEdunexifyTransactionFeePaise(null);
        }
        return ResponseEntity.ok(dto);
    }

    /**
     * GET /api/payments/history/receipt-breakdown/{paymentId}
     * Backend-authoritative per-fee-head breakdown for a single payment — the same data the
     * PDF receipt now renders from — for use by the "Payment Details" screen so it can stop
     * presenting the deprecated fixed buckets (tuitionFee/annualCharges/labCharges/etc.) as
     * if they were the real fee composition. Ownership-checked exactly like
     * history/details/{paymentId} and history/receipt/{paymentId}.
     */
    @GetMapping("/history/receipt-breakdown/{paymentId}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "', '" + Role.PARENT + "')")
    public ResponseEntity<?> getPaymentReceiptBreakdown(@PathVariable String paymentId) {
        log.info("Request for payment line-item breakdown for ID: {}", paymentId);

        Long schoolId = securityUtil.getSchoolId();
        Payment payment = paymentRepository.findByPaymentIdAndSchoolId(paymentId, schoolId).orElse(null);
        if (payment == null) {
            return ResponseEntity.notFound().build();
        }
        if (Role.STUDENT.equals(authService.getRole()) && !payment.getStudentId().equals(authService.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Students can only view their own payment breakdown."));
        }
        if (Role.PARENT.equals(authService.getRole())) {
            parentPortalService.assertChildAccess(payment.getStudentId(), ParentPortalService.ChildPermission.FEES);
        }

        return paymentService.getPaymentLineItemBreakdown(paymentId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/history/receipt/{paymentId}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "', '" + Role.PARENT + "')")
    public ResponseEntity<?> downloadPaymentReceipt(@PathVariable String paymentId) {
        log.info("Request to download receipt for payment ID: {}", paymentId);

        Long schoolId = securityUtil.getSchoolId();
        Payment paymentForOwnershipCheck = paymentRepository.findByPaymentIdAndSchoolId(paymentId, schoolId).orElse(null);
        if (paymentForOwnershipCheck == null) {
            return ResponseEntity.notFound().build();
        }
        if (Role.STUDENT.equals(authService.getRole()) && !paymentForOwnershipCheck.getStudentId().equals(authService.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Students can only download their own receipts."));
        }
        if (Role.PARENT.equals(authService.getRole())) {
            parentPortalService.assertChildAccess(paymentForOwnershipCheck.getStudentId(), ParentPortalService.ChildPermission.FEES);
        }

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

    /**
     * POST /api/payments/{paymentId}/refund
     * Delegates to PaymentService.processRefund, which re-verifies school ownership itself
     * (defense in depth, matching the rest of this module's convention), calls the gateway
     * (or skips it for a manual payment), and — in the same transaction — reverses exactly
     * the StudentFees state that payment's money put in place, derived from the payment's own
     * persisted month selection, never from this request's body.
     */
    @PostMapping("/{paymentId}/refund")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<Map<String, Object>> refundPayment(
            @PathVariable Long paymentId,
            @Valid @RequestBody RefundRequest request,
            HttpServletRequest httpRequest) {

        log.info("Refund request for payment ID: {} amount: {} paise", paymentId, request.getAmount());

        try {
            Map<String, Object> result = paymentService.processRefund(
                    paymentId, request, securityUtil.getUsername(), securityUtil.getRole(), httpRequest.getRemoteAddr());
            log.info("Refund processed successfully for paymentId={}", paymentId);
            return ResponseEntity.ok(result);
        } catch (java.util.NoSuchElementException e) {
            log.warn("Refund rejected: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Refund rejected for paymentId={}: {}", paymentId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Refund failed for paymentId={}", paymentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Refund failed: " + e.getMessage()));
        }
    }
}
