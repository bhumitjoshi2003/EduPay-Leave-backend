package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.FeeLineItemDto;
import com.indraacademy.ias_management.dto.PaymentLineItemBreakdownDto;
import com.indraacademy.ias_management.dto.PaymentResponseDTO;
import com.indraacademy.ias_management.dto.RefundRequest;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.PaymentStudentFeesAllocation;
import com.indraacademy.ias_management.entity.Refund;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentFeesLineItem;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.PaymentStudentFeesAllocationRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeesLineItemRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private FeeCalculationService feeCalculationService;
    @Autowired private ModelMapper modelMapper; // Retained, though not used in DTO mapping below
    @Autowired private SecurityUtil securityUtil;
    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private RazorpayService razorpayService;
    @Autowired private PaymentStudentFeesAllocationRepository paymentAllocationRepository;
    @Autowired private StudentFeesLineItemRepository studentFeesLineItemRepository;
    @Autowired private RefundSettlementService refundSettlementService;

    @Transactional(readOnly = true)
    public PaymentResponseDTO getPaymentHistoryDetails(String paymentId) {
        if (paymentId == null || paymentId.trim().isEmpty()) {
            log.warn("Attempted to get payment details with null/empty ID.");
            return null;
        }
        log.info("Fetching payment history details for payment ID: {}", paymentId);

        try {
            Long schoolId = securityUtil.getSchoolId();
            Payment payment = paymentRepository.findByPaymentIdAndSchoolId(paymentId, schoolId).orElse(null);
            if (payment == null) {
                log.warn("Payment not found with ID: {} for schoolId: {}", paymentId, schoolId);
                return null;
            }

            String sName = schoolRepository.findById(payment.getSchoolId() != null ? payment.getSchoolId() : -1L)
                    .map(School::getName).orElse("School");

            PaymentResponseDTO dto = new PaymentResponseDTO(
                    payment.getStudentId(),
                    payment.getStudentName(),
                    payment.getClassName(),
                    payment.getSession(),
                    payment.getMonth(),
                    payment.getAmount(),
                    payment.getPaymentId(),
                    payment.getOrderId(),
                    payment.getPaymentDate(),
                    payment.getStatus(),
                    payment.getBusFee(),
                    payment.getTuitionFee(),
                    payment.getAnnualCharges(),
                    payment.getLabCharges(),
                    payment.getEcaProject(),
                    payment.getExaminationFee(),
                    payment.getAmountPaid(),
                    payment.getAdditionalCharges() / 100,
                    payment.getLateFees() / 100,
                    payment.getPlatformFee() / 100
            );
            dto.setSchoolName(sName);
            boolean modern = OnlinePaymentPricingCalculator.PRICING_VERSION.equals(payment.getPricingVersion());
            long conveniencePaise = modern ? payment.getOnlineConvenienceFeePaise() : payment.getPlatformFee();
            long schoolDisplayPaise = modern
                    ? Math.addExact(payment.getSchoolLiabilityPrincipalPaise(), (long) payment.getAdditionalCharges())
                    : (long) payment.getAmount() - payment.getPlatformFee();
            dto.setSchoolFeePaise(schoolDisplayPaise);
            dto.setOnlineConvenienceFeePaise(conveniencePaise);
            dto.setTotalPaidPaise(payment.getAmount());
            dto.setPricingVersion(payment.getPricingVersion());
            dto.setSchoolLiabilityPrincipalPaise(payment.getSchoolLiabilityPrincipalPaise());
            dto.setGatewayRateBps(payment.getGatewayRateBps());
            dto.setGatewayTaxRateBps(payment.getGatewayTaxRateBps());
            dto.setGatewayRecoveryFeePaise(payment.getGatewayRecoveryFeePaise());
            dto.setEdunexifyTransactionFeePaise(payment.getEdunexifyTransactionFeePaise());
            return dto;
        } catch (DataAccessException e) {
            log.error("Data access error fetching payment details for ID: {}", paymentId, e);
            throw new RuntimeException("Could not retrieve payment details due to data access issue", e);
        }
    }

    /**
     * Backend-authoritative fee-head breakdown for a single payment — the aggregate, across
     * every StudentFees month this payment's PaymentStudentFeesAllocation rows reference, of
     * each month's real StudentFeesLineItem rows. Backs the PDF receipt and the "Payment
     * Details" screen; replaces the former practice of presenting Payment's five deprecated
     * fixed buckets (tuitionFee/annualCharges/labCharges/ecaProject/examinationFee) as if
     * they were the authoritative fee composition.
     *
     * Never fabricates: lineItemBreakdownAvailable is true only when EVERY allocated month
     * has real line-item data. A payment with no allocation rows at all (predating the
     * PaymentStudentFeesAllocation ledger) or covering even one month with no line items
     * returns lineItemBreakdownAvailable=false — the caller must show totalSchoolFeeDue (the
     * trusted total, resolveSchoolFeeDue — same figure the checkout quote and month-breakdown
     * endpoints use) with a "detailed breakdown unavailable" fallback, never a partial or
     * invented composition. totalSchoolFeeDue is null only when even the total can't be
     * resolved for one of the covered months.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentLineItemBreakdownDto> getPaymentLineItemBreakdown(String paymentId) {
        if (paymentId == null || paymentId.trim().isEmpty()) {
            return Optional.empty();
        }
        Long schoolId = securityUtil.getSchoolId();
        Payment payment = paymentRepository.findByPaymentIdAndSchoolId(paymentId, schoolId).orElse(null);
        if (payment == null) {
            return Optional.empty();
        }

        PaymentLineItemBreakdownDto dto = new PaymentLineItemBreakdownDto();
        dto.setPaymentId(paymentId);

        List<PaymentStudentFeesAllocation> allocations = paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(payment.getId());
        if (allocations.isEmpty()) {
            dto.setLineItems(List.of());
            dto.setLineItemBreakdownAvailable(false);
            dto.setTotalSchoolFeeDue(null);
            return Optional.of(dto);
        }

        List<Long> studentFeesIds = allocations.stream()
                .map(PaymentStudentFeesAllocation::getStudentFeesId)
                .distinct()
                .collect(java.util.stream.Collectors.toList());

        boolean everyMonthHasLineItems = true;
        boolean totalKnown = true;
        long totalSchoolFeeDuePaise = 0L;
        // (lineItemType, feeHeadName) -> {grossPaise, discountPaise, netPaise}
        Map<String, long[]> aggregated = new LinkedHashMap<>();
        Map<String, String[]> aggregatedMeta = new LinkedHashMap<>(); // key -> {lineItemType, feeHeadCode, feeHeadName}

        for (Long studentFeesId : studentFeesIds) {
            StudentFees fee = studentFeesRepository.findById(studentFeesId).orElse(null);
            if (fee == null) {
                log.warn("Payment {} allocation references missing StudentFees id {} — skipping for breakdown.", paymentId, studentFeesId);
                everyMonthHasLineItems = false;
                continue;
            }

            Optional<BigDecimal> monthDue = feeCalculationService.resolveSchoolFeeDue(fee, schoolId, fee.getYear());
            if (monthDue.isPresent()) {
                totalSchoolFeeDuePaise += monthDue.get().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            } else {
                totalKnown = false;
            }

            List<StudentFeesLineItem> rows = studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(studentFeesId);
            if (rows.isEmpty()) {
                everyMonthHasLineItems = false;
                continue;
            }
            for (StudentFeesLineItem li : rows) {
                String key = (li.getLineItemType() != null ? li.getLineItemType().name() : "?") + "|" + li.getFeeHeadName();
                long[] sums = aggregated.computeIfAbsent(key, k -> new long[3]);
                sums[0] += li.getGrossAmountPaise();
                sums[1] += li.getDiscountAmountPaise();
                sums[2] += li.getNetAmountPaise();
                aggregatedMeta.putIfAbsent(key, new String[]{
                        li.getLineItemType() != null ? li.getLineItemType().name() : null,
                        li.getFeeHeadCode(),
                        li.getFeeHeadName()
                });
            }
        }

        dto.setTotalSchoolFeeDue(totalKnown ? BigDecimal.valueOf(totalSchoolFeeDuePaise, 2) : null);

        if (!everyMonthHasLineItems) {
            dto.setLineItems(List.of());
            dto.setLineItemBreakdownAvailable(false);
            return Optional.of(dto);
        }

        List<FeeLineItemDto> lineItems = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : aggregated.entrySet()) {
            String[] meta = aggregatedMeta.get(entry.getKey());
            long[] sums = entry.getValue();
            FeeLineItemDto li = new FeeLineItemDto();
            li.setLineItemType(meta[0]);
            li.setFeeHeadCode(meta[1]);
            li.setFeeHeadName(meta[2]);
            li.setDiscountConfigType(null); // ambiguous once aggregated across months — not surfaced
            li.setGrossAmount(BigDecimal.valueOf(sums[0], 2));
            li.setDiscountAmount(BigDecimal.valueOf(sums[1], 2));
            li.setNetAmount(BigDecimal.valueOf(sums[2], 2));
            lineItems.add(li);
        }
        dto.setLineItems(lineItems);
        dto.setLineItemBreakdownAvailable(true);
        return Optional.of(dto);
    }

    public Page<Payment> gePaymentHistoryFiltered(String className, String studentId, LocalDate paymentDate, Pageable pageable) {
        log.info("Filtering payment history. Class: {}, Student ID: {}, Date: {}", className, studentId, paymentDate);
        Long schoolId = securityUtil.getSchoolId();

        // Sanitize studentId to strip SQL LIKE wildcard characters before passing to queries
        String safeStudentId = sanitizeLikeParam(studentId);

        try {
            if (className != null && safeStudentId != null && paymentDate != null) {
                return paymentRepository.findBySchoolIdAndClassNameAndStudentIdContainingAndPaymentDate(schoolId, className, safeStudentId, paymentDate, pageable);
            } else if (className != null && safeStudentId != null) {
                return paymentRepository.findBySchoolIdAndClassNameAndStudentIdContaining(schoolId, className, safeStudentId, pageable);
            } else if (className != null && paymentDate != null) {
                return paymentRepository.findBySchoolIdAndClassNameAndPaymentDate(schoolId, className, paymentDate, pageable);
            } else if (safeStudentId != null && paymentDate != null) {
                return paymentRepository.findBySchoolIdAndStudentIdContainingAndPaymentDate(schoolId, safeStudentId, paymentDate, pageable);
            } else if (className != null) {
                return paymentRepository.findBySchoolIdAndClassName(schoolId, className, pageable);
            } else if (safeStudentId != null) {
                return paymentRepository.findBySchoolIdAndStudentIdContaining(schoolId, safeStudentId, pageable);
            } else if (paymentDate != null) {
                return paymentRepository.findBySchoolIdAndPaymentDate(schoolId, paymentDate, pageable);
            } else {
                return paymentRepository.findBySchoolIdAndStudentIdContaining(schoolId, "", pageable);
            }
        } catch (DataAccessException e) {
            log.error("Data access error during payment history filtering. Class: {}, Student ID: {}, Date: {}", className, studentId, paymentDate, e);
            throw new RuntimeException("Could not retrieve filtered payment history due to data access issue", e);
        }
    }

    @Transactional(readOnly = true)
    public Page<Payment> getPaymentHistoryByStudentId(String studentId, Pageable pageable){
        if (studentId == null || studentId.trim().isEmpty()) {
            log.warn("Attempted to get payment history with null/empty student ID.");
            return Page.empty(pageable);
        }
        log.info("Fetching payment history for student ID: {}", studentId);

        try {
            return paymentRepository.findBySchoolIdAndStudentId(securityUtil.getSchoolId(), studentId, pageable);
        } catch (DataAccessException e) {
            log.error("Data access error fetching payment history for student ID: {}", studentId, e);
            throw new RuntimeException("Could not retrieve payment history by student ID due to data access issue", e);
        }
    }

    /**
     * Processes a refund against a payment. Refund-Integrity Hardening Phase B: this is now a
     * thin orchestrator over {@link RefundSettlementService}, which owns the actual atomic
     * reservation and finalization transactions — see its class javadoc for why that had to be
     * a separate {@code @Service} bean rather than private methods here (Spring's transactional
     * proxy cannot intercept a same-class method call).
     * <p>
     * Three phases, matching {@link RefundSettlementService}'s own Reserve/Call/Finalize shape:
     * <ol>
     *   <li>{@link RefundSettlementService#reserve} — locks the Payment, validates remaining
     *       refundable capacity against {@code payment.refundedAmountPaise}, and atomically
     *       creates a PENDING Refund + reserves capacity, all in one short transaction that
     *       commits (releasing the lock) before any network call.</li>
     *   <li>The Razorpay call happens here, in this method, with NO transaction/lock held. An
     *       ambiguous outcome (this codebase's RazorpayException carries no way to distinguish
     *       a definitive provider rejection from a network failure the provider may have
     *       already processed — see {@link RefundSettlementService#markFailedAndRelease}'s
     *       javadoc) leaves the refund PENDING and its capacity reserved; this method returns a
     *       pending-status response rather than throwing, since nothing has actually failed.</li>
     *   <li>{@link RefundSettlementService#finalizeSuccessfulRefund} — a fresh transaction that
     *       performs the exact allocation-ledger (or legacy bitmask) reversal this application
     *       has always used, unchanged in content, just re-homed so it can run in its own
     *       transaction after the provider call instead of holding the original lock through it.</li>
     * </ol>
     */
    public Map<String, Object> processRefund(Long paymentId, RefundRequest request,
                                              String actorUsername, String actorRole, String ipAddress) {
        Long schoolId = securityUtil.getSchoolId();

        RefundSettlementService.ReservationResult reservation = refundSettlementService.reserve(paymentId, request, schoolId);

        return switch (reservation.outcome()) {
            case REJECTED -> throw rejectionException(reservation.message());
            case ALREADY_RESERVED -> handleAlreadyReserved(reservation.refund());
            case RESERVED -> callProviderAndFinalize(paymentId, reservation, actorUsername, actorRole, ipAddress);
        };
    }

    /** Maps a Reserve-time rejection message to the exact exception type the pre-Phase-B code
     * threw for the equivalent situation, so PaymentController's existing catch blocks (and
     * every existing caller/test asserting on exception type) see no behavior change. */
    private RuntimeException rejectionException(String message) {
        if ("Payment not found.".equals(message)) {
            return new NoSuchElementException(message);
        }
        if (message.startsWith("Refund amount exceeds") || "Refund amount must be positive.".equals(message)) {
            return new IllegalArgumentException(message);
        }
        return new IllegalStateException(message);
    }

    /** A client-supplied idempotency key matched an existing Refund row. What that means
     * depends on the row's current status — reuse a still-PENDING reservation (never a second
     * provider call for it, since we cannot safely retry without provider-side idempotency —
     * see the Phase B report), report a prior definitive failure so the caller knows to retry
     * with a fresh request, or reject a genuine duplicate of an already-completed refund
     * exactly as the pre-Phase-B code did. */
    private Map<String, Object> handleAlreadyReserved(Refund existing) {
        if (RefundSettlementService.STATUS_PENDING.equals(existing.getStatus())) {
            log.info("Refund retry for paymentId={} matched an existing PENDING reservation (refundId={}) — " +
                    "not re-attempting the provider call; its outcome is still unconfirmed.",
                    existing.getPaymentId(), existing.getId());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("refundId", existing.getId());
            response.put("providerRefundId", existing.getProviderRefundId());
            response.put("amount", existing.getAmountPaise());
            response.put("status", "pending");
            response.put("message", "This refund is already in progress and could not yet be confirmed with the " +
                    "payment provider. Do not retry — it will be reconciled automatically.");
            return response;
        }
        if (RefundSettlementService.STATUS_FAILED.equals(existing.getStatus())) {
            throw new IllegalStateException("A previous refund attempt with this idempotency key failed (refundId="
                    + existing.getId() + "). Submit a new refund request with a different idempotency key to retry.");
        }
        throw new IllegalStateException("A refund with this idempotency key has already been processed for this payment.");
    }

    /**
     * Phase 2 (network call, no lock held) + Phase 3 (finalize) of the Reserve/Call/Finalize
     * flow — see {@link #processRefund}'s javadoc. Phase C: the provider's real returned status
     * now drives the branch, never assumed from "the call didn't throw":
     * <ul>
     *   <li>{@code processed} — provider refund id persisted immediately (before attempting
     *       finalize, so it survives even if finalize itself throws — see Task 11/{@link
     *       RefundSettlementService#recordProviderRefundId}), then finalize.</li>
     *   <li>{@code failed} — a definitive, synchronous provider rejection (not a network
     *       exception): release the reservation now, via the same primitive reconciliation uses.</li>
     *   <li>{@code pending} or any unrecognized status — persist the provider id if present
     *       (critical for later reconciliation), stay PENDING, return a pending response.</li>
     *   <li>a thrown exception (no response ever received) — ambiguous per the SDK limitation
     *       documented on {@link RefundSettlementService#markFailedAndRelease}; no provider id to
     *       persist, stay PENDING.</li>
     * </ul>
     */
    private Map<String, Object> callProviderAndFinalize(Long paymentId, RefundSettlementService.ReservationResult reservation,
                                                          String actorUsername, String actorRole, String ipAddress) {
        Refund refund = reservation.refund();
        Payment payment = reservation.payment();
        boolean isManualPayment = payment.getManualPaymentMode() != null;

        if (isManualPayment) {
            log.info("Refunding manual payment {} locally — no gateway call (mode={}).", paymentId, payment.getManualPaymentMode());
            return refundSettlementService.finalizeSuccessfulRefund(
                    paymentId, refund.getId(), null, actorUsername, actorRole, ipAddress);
        }

        RazorpayService.ProviderRefundResult providerResult;
        try {
            providerResult = razorpayService.createRefund(payment.getPaymentId(), refund.getAmountPaise(), refund.getReason());
        } catch (RuntimeException e) {
            log.warn("Ambiguous provider outcome for refundId={} paymentId={} — leaving PENDING, capacity " +
                    "remains reserved. This is NOT treated as a failure.", refund.getId(), paymentId, e);
            return pendingResponse(refund.getId(), null, refund.getAmountPaise(),
                    "Refund could not be confirmed with the payment provider. It has not failed — do not retry. " +
                            "It will be reconciled automatically.");
        }

        String providerRefundId = providerResult.refundId();
        String providerStatus = providerResult.status();

        if (RazorpayService.PROVIDER_STATUS_PROCESSED.equals(providerStatus)) {
            if (providerRefundId != null) {
                refundSettlementService.recordProviderRefundId(paymentId, refund.getId(), providerRefundId);
            }
            try {
                return refundSettlementService.finalizeSuccessfulRefund(
                        paymentId, refund.getId(), providerRefundId, actorUsername, actorRole, ipAddress);
            } catch (RuntimeException e) {
                log.error("Provider refund succeeded (paymentId={} refundId={} providerRefundId={}, already " +
                        "persisted) but local finalization failed — the refund remains PENDING and must be " +
                        "reconciled (retrying finalize only), NOT retried as a new refund.",
                        paymentId, refund.getId(), providerRefundId, e);
                throw e;
            }
        }

        if (RazorpayService.PROVIDER_STATUS_FAILED.equals(providerStatus)) {
            log.warn("Provider definitively reported refundId={} (providerRefundId={}) as failed at creation " +
                    "time — releasing reservation.", refund.getId(), providerRefundId);
            refundSettlementService.markFailedAndRelease(paymentId, refund.getId());
            throw new IllegalStateException("Refund was rejected by the payment provider.");
        }

        // pending, or any status this codebase doesn't recognize — conservative: persist
        // whatever provider id we DO have (Task 4: as early as possible), stay PENDING.
        if (providerRefundId != null) {
            refundSettlementService.recordProviderRefundId(paymentId, refund.getId(), providerRefundId);
        }
        return pendingResponse(refund.getId(), providerRefundId, refund.getAmountPaise(),
                "Refund was accepted by the payment provider and is still processing. It will be finalized " +
                        "automatically once confirmed.");
    }

    private Map<String, Object> pendingResponse(Long refundId, String providerRefundId, long amountPaise, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("refundId", refundId);
        response.put("providerRefundId", providerRefundId);
        response.put("amount", amountPaise);
        response.put("status", "pending");
        response.put("message", message);
        return response;
    }

    public byte[] generatePaymentReceiptPdf(String paymentId) {
        if (paymentId == null || paymentId.trim().isEmpty()) {
            log.error("Cannot generate PDF: Payment ID is null or empty.");
            return null;
        }
        log.info("Starting PDF generation for payment ID: {}", paymentId);

        Payment payment;
        try {
            Long schoolId = securityUtil.getSchoolId();
            payment = paymentRepository.findByPaymentIdAndSchoolId(paymentId, schoolId).orElse(null);
        } catch (DataAccessException e) {
            log.error("Data access error fetching payment for PDF generation ID: {}", paymentId, e);
            throw new RuntimeException("Could not retrieve payment data for PDF due to data access issue", e);
        }

        if (payment == null) {
            log.warn("Payment not found for PDF generation ID: {} for current school", paymentId);
            return null;
        }

        try {
            String html = buildReceiptHtml(payment);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(baos);
            log.info("PDF generated successfully for payment ID: {}", paymentId);
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Error generating PDF for payment ID: {}", paymentId, e);
            throw new RuntimeException("Failed to generate PDF receipt.", e);
        }
    }

    /** Converts a 12-char academic-month bitmask to real calendar month names, using the
     * given school's configured academicYearStartMonth (bit position i = academic month
     * i+1, per StudentFees.month's convention) — never a hardcoded April-first array. */
    private String getMonthNamesFromBinary(String monthBinary, School school) {
        if (monthBinary == null || monthBinary.length() != 12) {
            return "N/A";
        }

        int startMonth = school != null ? school.getAcademicYearStartMonth() : 4;

        List<String> selectedMonths = new ArrayList<>();
        for (int i = 0; i < monthBinary.length(); i++) {
            if (monthBinary.charAt(i) == '1') {
                selectedMonths.add(feeCalculationService.getMonthName(i + 1, startMonth));
            }
        }

        return selectedMonths.isEmpty() ? "No Month Selected" : String.join(", ", selectedMonths);
    }

    private String buildReceiptHtml(Payment payment) {
        // Resolve school info dynamically
        School school = schoolRepository.findById(payment.getSchoolId() != null ? payment.getSchoolId() : -1L).orElse(null);
        String schoolName = school != null ? school.getName() : "School";
        String schoolSubLine = buildSchoolSubLine(school);

        // Embed logo as base64 data URI so Flying Saucer can render it without filesystem access
        String logoDataUri = "";
        try {
            byte[] logoBytes = new ClassPathResource("images/logo.png").getInputStream().readAllBytes();
            logoDataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(logoBytes);
        } catch (IOException e) {
            log.warn("Logo not found, PDF will be generated without it.");
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");
        String paymentDate = payment.getPaymentDate() != null
                ? payment.getPaymentDate().format(fmt) : "N/A";
        String status = payment.getStatus() != null
                ? payment.getStatus().toUpperCase() : "N/A";
        String paymentMode = payment.isPaidManually() ? "Cash / Manual" : "Online (Razorpay)";
        String generatedOn = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"));
        String formattedMonths = getMonthNamesFromBinary(payment.getMonth(), school);

        // Build fee rows from the authoritative per-fee-head breakdown — never the deprecated
        // fixed buckets (tuitionFee/annualCharges/labCharges/ecaProject/examinationFee),
        // which are no longer read here at all. Falls back to the trusted total, then to an
        // explicit "unavailable" note, per PaymentLineItemBreakdownDto's never-fabricate
        // contract — see getPaymentLineItemBreakdown's javadoc.
        PaymentLineItemBreakdownDto breakdown = getPaymentLineItemBreakdown(payment.getPaymentId()).orElse(null);
        StringBuilder feeRows = new StringBuilder();
        int rowIdx = 0;
        if (OnlinePaymentPricingCalculator.PRICING_VERSION.equals(payment.getPricingVersion())) {
            long schoolDisplayPaise = Math.addExact(payment.getSchoolLiabilityPrincipalPaise(),
                    (long) payment.getAdditionalCharges());
            rowIdx = appendFeeRow(feeRows, "School Fee", BigDecimal.valueOf(schoolDisplayPaise, 2), rowIdx);
            rowIdx = appendFeeRow(feeRows, "Online Convenience Fee",
                    BigDecimal.valueOf(payment.getOnlineConvenienceFeePaise(), 2), rowIdx);
        } else if (breakdown != null && breakdown.isLineItemBreakdownAvailable()) {
            for (FeeLineItemDto li : breakdown.getLineItems()) {
                rowIdx = appendFeeRow(feeRows, li.getFeeHeadName(), li.getGrossAmount(), rowIdx);
                if (li.getDiscountAmount() != null && li.getDiscountAmount().signum() > 0) {
                    rowIdx = appendFeeRow(feeRows, li.getFeeHeadName() + " Discount", li.getDiscountAmount().negate(), rowIdx);
                }
            }
        } else if (breakdown != null && breakdown.getTotalSchoolFeeDue() != null) {
            rowIdx = appendFeeRow(feeRows, "School Fee (breakdown unavailable)", breakdown.getTotalSchoolFeeDue(), rowIdx);
        } else {
            appendMutedRow(feeRows, "Detailed fee breakdown unavailable for this payment");
        }
        if (!OnlinePaymentPricingCalculator.PRICING_VERSION.equals(payment.getPricingVersion())) {
            rowIdx = appendFeeRow(feeRows, "Leave Charges", BigDecimal.valueOf(payment.getAdditionalCharges(), 2), rowIdx);
            rowIdx = appendFeeRow(feeRows, "Late Fees", BigDecimal.valueOf(payment.getLateFees(), 2), rowIdx);
            appendFeeRow(feeRows, "Platform Fee", BigDecimal.valueOf(payment.getPlatformFee(), 2), rowIdx);
        }

        String logoHtml = logoDataUri.isEmpty() ? ""
                : "<img src=\"" + logoDataUri + "\" style=\"width: 75pt; height: 75pt;\" alt=\"logo\"/><br/>";

        String statusClass = "SUCCESS".equals(status) ? "status-success" : "status-fail";

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
             + "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\""
             + " \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n"
             + "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n"
             + "<head>\n"
             + "  <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"/>\n"
             + "  <style type=\"text/css\">\n"
             + "    @page { size: A4; margin: 14mm 16mm 14mm 16mm; }\n"
             + "    body  { font-family: Arial, Helvetica, sans-serif; font-size: 9pt;"
             + "            color: #1A1A1A; margin: 0; padding: 0; }\n"
             /* ── Header ─────────────────────────────────────────── */
             + "    .header       { text-align: center; padding-bottom: 8pt;"
             + "                   border-bottom: 3pt solid #C8960C; }\n"
             + "    .school-name  { font-size: 16pt; font-weight: bold; color: #1B3A6B;"
             + "                   margin: 6pt 0 2pt 0; }\n"
             + "    .school-sub   { font-size: 7.5pt; color: #666666; margin: 0; }\n"
             /* ── Title bar ──────────────────────────────────────── */
             + "    .title-bar    { background-color: #1B3A6B; color: #FFFFFF;"
             + "                   text-align: center; padding: 5pt 0 4pt 0; margin: 9pt 0 9pt 0; }\n"
             + "    .title-main   { font-size: 13pt; font-weight: bold;"
             + "                   letter-spacing: 2pt; margin: 0; }\n"
             + "    .title-sub    { font-size: 7.5pt; margin: 2pt 0 0 0; color: #C8D8F0; }\n"
             /* ── Two-column info cards ───────────────────────────── */
             + "    .info-outer   { width: 100%; border-collapse: collapse; margin-bottom: 9pt; }\n"
             + "    .info-card    { width: 49%; vertical-align: top;"
             + "                   border: 1pt solid #BFC9D9; }\n"
             + "    .card-gap     { width: 2%; }\n"
             + "    .card-header-student { background-color: #1B3A6B; color: #FFFFFF;"
             + "                   font-weight: bold; font-size: 8pt; padding: 3pt 7pt; }\n"
             + "    .card-header-payment { background-color: #0D6B6B; color: #FFFFFF;"
             + "                   font-weight: bold; font-size: 8pt; padding: 3pt 7pt; }\n"
             + "    .card-body    { width: 100%; border-collapse: collapse; }\n"
             + "    .card-row td  { padding: 3pt 7pt; font-size: 8.5pt;"
             + "                   border-bottom: 1pt solid #DDE6F2; }\n"
             + "    .lbl          { color: #666666; width: 44%; }\n"
             + "    .val          { font-weight: bold; color: #1A1A1A; }\n"
             + "    .status-success { color: #1B7C2A; font-weight: bold; }\n"
             + "    .status-fail    { color: #C62828; font-weight: bold; }\n"
             /* ── Fee breakdown table ─────────────────────────────── */
             + "    .fee-table    { width: 100%; border-collapse: collapse;"
             + "                   border: 1pt solid #BFC9D9; }\n"
             + "    .fee-th       { background-color: #1B3A6B; color: #FFFFFF;"
             + "                   font-size: 9pt; font-weight: bold; padding: 4pt 9pt; text-align: left; }\n"
             + "    .fee-th-amt   { background-color: #1B3A6B; color: #FFFFFF;"
             + "                   font-size: 9pt; font-weight: bold; padding: 4pt 9pt; text-align: right; }\n"
             + "    .fee-even td  { background-color: #FFFFFF; padding: 3.5pt 9pt;"
             + "                   font-size: 8.5pt; border-bottom: 1pt solid #DDE6F2; }\n"
             + "    .fee-odd  td  { background-color: #F0F4FA; padding: 3.5pt 9pt;"
             + "                   font-size: 8.5pt; border-bottom: 1pt solid #DDE6F2; }\n"
             + "    .amt-col      { text-align: right; font-weight: bold; }\n"
             + "    .fee-muted td { background-color: #FAFBFD; padding: 3.5pt 9pt;"
             + "                   font-size: 8pt; font-style: italic; color: #9AA5B5;"
             + "                   border-bottom: 1pt solid #DDE6F2; text-align: center; }\n"
             /* ── Totals ──────────────────────────────────────────── */
             + "    .total-table  { width: 100%; border-collapse: collapse;"
             + "                   border: 1pt solid #BFC9D9; margin-top: 0; }\n"
             + "    .subtotal-row td { background-color: #E8EDF5; padding: 4.5pt 9pt;"
             + "                      font-size: 9pt; font-weight: bold; color: #1B3A6B;"
             + "                      border-bottom: 1pt solid #BFC9D9; }\n"
             + "    .paid-row td  { background-color: #C8960C; color: #FFFFFF;"
             + "                   padding: 5.5pt 9pt; font-size: 10.5pt; font-weight: bold; }\n"
             + "    .right        { text-align: right; }\n"
             /* ── Signature & footer ──────────────────────────────── */
             + "    .sig-area     { text-align: right; margin-top: 24pt;"
             + "                   font-size: 8pt; color: #555555; }\n"
             + "    .footer       { margin-top: 14pt; border-top: 2pt solid #C8960C;"
             + "                   padding-top: 6pt; text-align: center;"
             + "                   font-size: 7pt; color: #999999; }\n"
             + "  </style>\n"
             + "</head>\n"
             + "<body>\n"
             /* ── Header ─────────────────────────────────────────── */
             + "  <div class=\"header\">\n"
             + "    " + logoHtml + "\n"
             + "    <p class=\"school-name\">" + esc(schoolName) + "</p>\n"
             + "    <p class=\"school-sub\">" + esc(schoolSubLine) + "</p>\n"
             + "  </div>\n"
             /* ── Title bar ──────────────────────────────────────── */
             + "  <div class=\"title-bar\">\n"
             + "    <p class=\"title-main\">FEE RECEIPT</p>\n"
             + "    <p class=\"title-sub\">Month: " + esc(formattedMonths)
             +                        " &#160;|&#160; Session: " + esc(payment.getSession()) + "</p>\n"
             + "  </div>\n"
             /* ── Two-column info cards ───────────────────────────── */
             + "  <table class=\"info-outer\"><tr>\n"
             + "    <td class=\"info-card\">\n"
             + "      <div class=\"card-header-student\">STUDENT INFORMATION</div>\n"
             + "      <table class=\"card-body\"><tbody>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Student ID</td>"
             +           "<td class=\"val\">" + esc(payment.getStudentId()) + "</td></tr>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Name</td>"
             +           "<td class=\"val\">" + esc(payment.getStudentName()) + "</td></tr>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Class</td>"
             +           "<td class=\"val\">" + esc(payment.getClassName()) + "</td></tr>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Session</td>"
             +           "<td class=\"val\">" + esc(payment.getSession()) + "</td></tr>\n"
             + "      </tbody></table>\n"
             + "    </td>\n"
             + "    <td class=\"card-gap\"></td>\n"
             + "    <td class=\"info-card\">\n"
             + "      <div class=\"card-header-payment\">PAYMENT DETAILS</div>\n"
             + "      <table class=\"card-body\"><tbody>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Receipt No.</td>"
             +           "<td class=\"val\">" + esc(payment.getPaymentId()) + "</td></tr>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Date &amp; Time</td>"
             +           "<td class=\"val\">" + esc(paymentDate) + "</td></tr>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Mode</td>"
             +           "<td class=\"val\">" + paymentMode + "</td></tr>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Status</td>"
             +           "<td class=\"val\"><span class=\"" + statusClass + "\">" + status + "</span></td></tr>\n"
             + "      </tbody></table>\n"
             + "    </td>\n"
             + "  </tr></table>\n"
             /* ── Fee breakdown ───────────────────────────────────── */
             + "  <table class=\"fee-table\"><thead>\n"
             + "    <tr><th class=\"fee-th\">Fee Description</th>"
             +          "<th class=\"fee-th-amt\">Amount (Rs.)</th></tr>\n"
             + "  </thead><tbody>\n"
             + feeRows
             + "  </tbody></table>\n"
             /* ── Totals ──────────────────────────────────────────── */
             + "  <table class=\"total-table\"><tbody>\n"
             + "    <tr class=\"subtotal-row\">"
             +       "<td>Total Fees Charged</td>"
             +       "<td class=\"right\">Rs. " + formatPaise(payment.getAmount()) + "</td></tr>\n"
             + "    <tr class=\"paid-row\">"
             +       "<td>Amount Paid</td>"
             +       "<td class=\"right\">Rs. " + formatPaise(payment.getAmountPaid()) + "</td></tr>\n"
             + "  </tbody></table>\n"
             /* ── Signature ───────────────────────────────────────── */
             + "  <div class=\"sig-area\">\n"
             + "    <p>Authorised Signatory</p>\n"
             + "    <p>_________________________</p>\n"
             + "    <p>For " + esc(schoolName) + "</p>\n"
             + "  </div>\n"
             /* ── Footer ─────────────────────────────────────────── */
             + "  <div class=\"footer\">\n"
             + "    <p>This is a computer-generated receipt and does not require a physical signature.</p>\n"
             + "    <p>Generated on: " + generatedOn + "</p>\n"
             + "  </div>\n"
             + "</body>\n"
             + "</html>";
    }

    /**
     * Builds the sub-header line for the PDF receipt using dynamic school fields.
     * Falls back gracefully when fields are absent.
     */
    private String buildSchoolSubLine(School school) {
        if (school == null) return "";
        StringBuilder sb = new StringBuilder();
        if (school.getAddress() != null && !school.getAddress().isBlank()) {
            sb.append(school.getAddress().trim());
        }
        if (school.getBoardType() != null) {
            if (sb.length() > 0) sb.append(" \u00a0|\u00a0 ");
            sb.append("Affiliated to ").append(school.getBoardType().name());
        }
        return sb.toString();
    }

    /** Appends a fee row unless amount is exactly zero — a negative amount (a per-fee-head
     * discount row) is intentionally allowed through by design, only zero rows are skipped.
     * Returns the incremented row index. */
    private int appendFeeRow(StringBuilder sb, String label, BigDecimal amount, int rowIdx) {
        if (amount == null || amount.signum() == 0) return rowIdx;
        String cls = (rowIdx % 2 == 0) ? "fee-even" : "fee-odd";
        sb.append("    <tr class=\"").append(cls).append("\">")
          .append("<td>").append(esc(label)).append("</td>")
          .append("<td class=\"amt-col\">").append(amount.setScale(2, RoundingMode.HALF_UP).toPlainString()).append("</td>")
          .append("</tr>\n");
        return rowIdx + 1;
    }

    /** A single-column, full-width note row (e.g. "Detailed breakdown unavailable") — no
     * amount, so it can't be mistaken for a real charge. */
    private void appendMutedRow(StringBuilder sb, String label) {
        sb.append("    <tr class=\"fee-muted\"><td colspan=\"2\">")
          .append(esc(label))
          .append("</td></tr>\n");
    }

    /** Payment.amount/amountPaid are stored in paise (int) — this converts to a rupees
     * string for display. Found via end-to-end testing: the totals section previously
     * concatenated the raw paise int directly (e.g. "Rs. 950100" for an actual ₹9,501.00
     * payment), a 100x display error that the per-fee-head rows above it didn't share
     * (those already receive pre-converted rupee BigDecimals from the breakdown DTO). */
    private String formatPaise(int paise) {
        return BigDecimal.valueOf(paise, 2).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Strips SQL LIKE wildcard characters (%, _, \) from a search term so they
     * cannot be used to craft unintended broad-match patterns in LIKE queries.
     * Returns null when the input is null (preserves the "no filter" semantic).
     */
    private String sanitizeLikeParam(String value) {
        if (value == null) return null;
        return value.replaceAll("[%_\\\\]", "");
    }

    /** Minimal HTML escaping for user-supplied strings. */
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
