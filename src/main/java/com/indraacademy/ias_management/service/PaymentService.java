package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.FeeLineItemDto;
import com.indraacademy.ias_management.dto.PaymentLineItemBreakdownDto;
import com.indraacademy.ias_management.dto.PaymentResponseDTO;
import com.indraacademy.ias_management.dto.RefundRequest;
import com.indraacademy.ias_management.entity.AllocationRefund;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.PaymentStudentFeesAllocation;
import com.indraacademy.ias_management.entity.Refund;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentFeesLineItem;
import com.indraacademy.ias_management.repository.AllocationRefundRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.PaymentStudentFeesAllocationRepository;
import com.indraacademy.ias_management.repository.RefundRepository;
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
    @Autowired private RefundRepository refundRepository;
    @Autowired private RazorpayService razorpayService;
    @Autowired private AuditService auditService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PaymentStudentFeesAllocationRepository paymentAllocationRepository;
    @Autowired private AllocationRefundRepository allocationRefundRepository;
    @Autowired private StudentFeesLineItemRepository studentFeesLineItemRepository;

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
                    payment.getAdditionalCharges(),
                    payment.getLateFees(),
                    payment.getPlatformFee()
            );
            dto.setSchoolName(sName);
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

    /** Same rounding-only tolerance as StudentFeesService uses for "fully paid" — kept as its
     * own constant here rather than shared, matching this codebase's existing per-service
     * tolerance-constant convention (e.g. PaymentController.AMOUNT_MISMATCH_TOLERANCE_PAISE). */
    private static final long ROW_FULLY_PAID_TOLERANCE_PAISE = 100L; // ₹1

    /**
     * Processes a refund against a payment: calls the payment gateway (or skips it for a
     * manual payment, which has no gateway leg), then reverses exactly the persisted payment
     * allocations that payment's money put in place, and records the refund event. Everything
     * after the (irreversible, external) gateway call happens in one transaction, with the
     * Payment row pessimistic-locked for its duration so two concurrent refund attempts
     * against the same payment serialize rather than both reading the same "already refunded"
     * total.
     * <p>
     * Reversal is allocation-ledger-based (payment_student_fees_allocation /
     * allocation_refund) whenever the payment has allocation rows — the normal case for any
     * payment recorded after this ledger was introduced. Exactly which allocations were
     * reversed, and by how much, is persisted; a StudentFees row's paid/amountPaid is then
     * recomputed from its TOTAL net allocation across every payment that ever touched it
     * (not just this one), which is what lets this correctly handle a row that received money
     * from more than one payment. Only payments that predate the ledger (no allocation rows
     * at all) fall back to the old approximate oldest-month-first reversal against
     * Payment.month — those refunds are flagged legacyApproximation=true so they're
     * queryable/reviewable separately. The original fee snapshot (baseAmountDue, busFeeDue,
     * discountAmount, amountRuleSnapshot) is never touched by either path — a refund reverses
     * payment state, it never recalculates what was originally charged.
     */
    @Transactional
    public Map<String, Object> processRefund(Long paymentId, RefundRequest request,
                                              String actorUsername, String actorRole, String ipAddress) {
        Long schoolId = securityUtil.getSchoolId();

        // Pessimistic write lock for the rest of this transaction — a second, concurrent
        // refund attempt against the same payment blocks here until this one commits or
        // rolls back, instead of both computing "already refunded" from the same stale read.
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null || !schoolId.equals(payment.getSchoolId())) {
            log.warn("Refund rejected: payment not found or does not belong to school. paymentId={} schoolId={}", paymentId, schoolId);
            throw new NoSuchElementException("Payment not found.");
        }

        // Unlike the equivalent checks in RazorpayService.verifyPayment and
        // recordManualPayment, this one doesn't need a separate catch-and-convert for a
        // races-past-the-check duplicate: the pessimistic write lock on `payment` acquired
        // above (findByIdForUpdate) already serializes every refund attempt against this
        // exact paymentId, so a second concurrent request with the same idempotency key
        // blocks here until the first commits, then correctly sees it via this check — the
        // race window this pattern exists to close elsewhere is already closed by the lock.
        // refund(payment_id, idempotency_key)'s partial unique index (V31) remains as a
        // backstop in case that invariant is ever weakened by a future change.
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()
                && refundRepository.existsByPaymentIdAndIdempotencyKey(paymentId, request.getIdempotencyKey())) {
            log.warn("Refund rejected: duplicate idempotency key '{}' for paymentId={}", request.getIdempotencyKey(), paymentId);
            throw new IllegalStateException("A refund with this idempotency key has already been processed for this payment.");
        }

        long alreadyRefundedPaise = refundRepository.sumAmountPaiseByPaymentId(paymentId);
        long remainingRefundablePaise = payment.getAmountPaid() - alreadyRefundedPaise;
        if (remainingRefundablePaise <= 0) {
            log.warn("Refund rejected: payment {} is already fully refunded (refunded so far={} paise, paid={} paise).",
                    paymentId, alreadyRefundedPaise, payment.getAmountPaid());
            throw new IllegalStateException("This payment has already been fully refunded.");
        }
        if (request.getAmount() > remainingRefundablePaise) {
            log.warn("Refund rejected: requested {} paise exceeds remaining refundable {} paise for paymentId={}",
                    request.getAmount(), remainingRefundablePaise, paymentId);
            throw new IllegalArgumentException(
                    "Refund amount exceeds the remaining refundable balance (" + remainingRefundablePaise + " paise).");
        }

        // 1. Gateway call first (or skip for a manual payment) — an exception here leaves no
        //    row mutated, so there is nothing to roll back.
        boolean isManualPayment = payment.getManualPaymentMode() != null;
        String providerRefundId = null;
        if (isManualPayment) {
            log.info("Refunding manual payment {} locally — no gateway call (mode={}).", paymentId, payment.getManualPaymentMode());
        } else {
            String razorpayPaymentId = payment.getPaymentId();
            if (razorpayPaymentId == null || razorpayPaymentId.isBlank()) {
                throw new IllegalStateException("Cannot refund: no Razorpay payment ID associated with this record.");
            }
            Map<String, Object> refundResult = razorpayService.createRefund(razorpayPaymentId, request.getAmount(), request.getReason());
            providerRefundId = String.valueOf(refundResult.get("refundId"));
        }

        // 2. Reverse via the allocation ledger when this payment has one; fall back to the
        //    approximate bitmask-based reversal only for a pre-ledger legacy payment.
        List<PaymentStudentFeesAllocation> allocations = paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(paymentId);
        boolean legacyApproximation = allocations.isEmpty();
        String monthsReversedMask;
        List<Integer> monthsActuallyTouched;
        Refund refund = new Refund();
        refund.setPaymentId(paymentId);
        refund.setSchoolId(schoolId);
        refund.setStudentId(payment.getStudentId());
        refund.setSession(payment.getSession());
        refund.setAmountPaise(request.getAmount());
        refund.setReason(request.getReason());
        refund.setProviderRefundId(providerRefundId);
        refund.setStatus("success");
        refund.setIdempotencyKey(request.getIdempotencyKey());
        refund.setInitiatedBy(actorUsername);
        refund.setLegacyApproximation(legacyApproximation);

        if (!legacyApproximation) {
            // Pass 1: compute the reversal plan (which allocations, how much of each) without
            // persisting anything yet — mirrors markFeesAsPaid's own compute-then-apply shape.
            record AllocationReversalPlan(PaymentStudentFeesAllocation allocation, long portionPaise) {}
            List<AllocationReversalPlan> plan = new ArrayList<>();
            long remainingToRefund = request.getAmount();
            for (PaymentStudentFeesAllocation allocation : allocations) {
                if (remainingToRefund <= 0) break;
                long alreadyReversed = allocationRefundRepository.sumAmountPaiseByAllocationId(allocation.getId());
                long remainingInAllocation = allocation.getAmountPaise() - alreadyReversed;
                if (remainingInAllocation <= 0) continue; // already fully reversed by a prior refund
                long portion = Math.min(remainingToRefund, remainingInAllocation);
                plan.add(new AllocationReversalPlan(allocation, portion));
                remainingToRefund -= portion;
            }
            if (plan.isEmpty()) {
                // remainingRefundablePaise (payment-level) said there was room, but the
                // allocation-level ledger disagrees — a real inconsistency, not something to
                // silently paper over with a refund that reverses nothing.
                log.error("Refund inconsistency for paymentId={}: payment-level ledger allows {} paise but no allocation has any remaining balance to reverse.",
                        paymentId, request.getAmount());
                throw new IllegalStateException("Cannot reconcile refund amount against this payment's allocation ledger.");
            }

            StringBuilder mask = new StringBuilder("000000000000");
            monthsActuallyTouched = new ArrayList<>();
            for (AllocationReversalPlan p : plan) {
                mask.setCharAt(p.allocation().getMonth() - 1, '1');
                monthsActuallyTouched.add(p.allocation().getMonth());
            }
            monthsReversedMask = mask.toString();
            refund.setMonthsRefunded(monthsReversedMask);
            refundRepository.save(refund); // need refund.getId() before writing AllocationRefund rows

            for (AllocationReversalPlan p : plan) {
                AllocationRefund allocationRefund = new AllocationRefund();
                allocationRefund.setAllocationId(p.allocation().getId());
                allocationRefund.setRefundId(refund.getId());
                allocationRefund.setStudentFeesId(p.allocation().getStudentFeesId());
                allocationRefund.setAmountPaise(p.portionPaise());
                allocationRefundRepository.save(allocationRefund);
            }

            // Recompute each touched StudentFees row from its TOTAL net allocation across
            // every payment that ever contributed to it — not just this one — which is what
            // correctly handles a row funded by more than one payment.
            for (Long studentFeesId : plan.stream().map(p -> p.allocation().getStudentFeesId()).distinct().toList()) {
                StudentFees fee = studentFeesRepository.findByIdForUpdate(studentFeesId);
                if (fee != null) {
                    recomputeStudentFeesNetState(fee, schoolId);
                }
            }
        } else {
            log.warn("Payment {} predates the allocation ledger — falling back to approximate oldest-month-first reversal against Payment.month.", paymentId);
            LegacyReversalResult legacyResult = reverseLegacyByMonthBitmask(payment, schoolId, request.getAmount());
            monthsReversedMask = legacyResult.monthsReversedMask();
            monthsActuallyTouched = legacyResult.monthsTouched();
            refund.setMonthsRefunded(monthsReversedMask);
            refundRepository.save(refund);
        }

        // 3. Update Payment status, audit log — same transaction as everything above.
        long totalRefundedNowPaise = alreadyRefundedPaise + request.getAmount();
        payment.setStatus(totalRefundedNowPaise >= payment.getAmountPaid() ? "refunded" : "partially_refunded");
        paymentRepository.save(payment);

        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("paymentId", paymentId);
            details.put("refundId", refund.getId());
            details.put("providerRefundId", providerRefundId);
            details.put("studentId", payment.getStudentId());
            details.put("session", payment.getSession());
            details.put("monthsRefunded", monthsReversedMask);
            details.put("amountPaise", request.getAmount());
            details.put("legacyApproximation", legacyApproximation);
            details.put("actor", actorUsername);
            details.put("timestamp", LocalDateTime.now().toString());
            auditService.log(actorUsername, actorRole, "REFUND_PAYMENT", "Payment", paymentId.toString(),
                    null, objectMapper.writeValueAsString(details), ipAddress != null ? ipAddress : "SYSTEM");
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        log.info("Refund processed for paymentId={} refundId={} amount={} paise, months touched={}, legacyApproximation={}.",
                paymentId, refund.getId(), request.getAmount(), monthsActuallyTouched, legacyApproximation);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("refundId", refund.getId());
        response.put("providerRefundId", providerRefundId);
        response.put("amount", request.getAmount());
        response.put("status", refund.getStatus());
        response.put("monthsRefunded", monthsReversedMask);
        response.put("legacyApproximation", legacyApproximation);
        return response;
    }

    /** Recomputes a StudentFees row's paid/amountPaid from its TOTAL net allocation (gross
     * allocations minus gross reversals against them, across every payment that ever touched
     * it) — the single place this happens for both a fresh allocation (StudentFeesService)
     * and a reversal (here), so the two can never disagree on what "net" means. Never touches
     * baseAmountDue/busFeeDue/discountAmount/amountRuleSnapshot.
     * <p>
     * manuallyPaid/manualPaymentReceived are likewise derived from the ledger here — the net
     * amount specifically contributed by payments with a manualPaymentMode set — rather than
     * carried over as a separate "last write wins" flag. For a row funded by exactly one
     * payment (the overwhelming common case) this is unchanged from before; for a row funded
     * by a mix of manual and gateway payments, manualPaymentReceived now honestly reflects
     * only the manual portion instead of conflating it with the row's full total. */
    private void recomputeStudentFeesNetState(StudentFees fee, Long schoolId) {
        long grossAllocated = paymentAllocationRepository.sumAmountPaiseByStudentFeesId(fee.getId());
        long grossReversed = allocationRefundRepository.sumAmountPaiseByStudentFeesId(fee.getId());
        long netPaise = Math.max(0, grossAllocated - grossReversed);
        BigDecimal netAmount = BigDecimal.valueOf(netPaise, 2);
        fee.setAmountPaid(netAmount);

        long grossManualAllocated = paymentAllocationRepository.sumManualAmountPaiseByStudentFeesId(fee.getId());
        long grossManualReversed = allocationRefundRepository.sumManualReversedAmountPaiseByStudentFeesId(fee.getId());
        long netManualPaise = Math.max(0, grossManualAllocated - grossManualReversed);
        fee.setManuallyPaid(netManualPaise > 0);
        fee.setManualPaymentReceived(netManualPaise > 0 ? BigDecimal.valueOf(netManualPaise, 2) : BigDecimal.ZERO);

        Optional<BigDecimal> due = feeCalculationService.resolveSchoolFeeDue(fee, schoolId, fee.getYear());
        boolean fullyPaid;
        if (due.isPresent()) {
            long duePaise = due.get().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            fullyPaid = netPaise >= (duePaise - ROW_FULLY_PAID_TOLERANCE_PAISE);
        } else {
            // The original due can no longer be resolved (e.g. rule config changed since) —
            // don't let that block a legitimate refund; fall back to "any net money = paid".
            fullyPaid = netPaise > 0;
        }
        fee.setPaid(fullyPaid);
        studentFeesRepository.save(fee);
    }

    private record LegacyReversalResult(String monthsReversedMask, List<Integer> monthsTouched) {}

    /** The pre-ledger approximation kept ONLY for refunding a payment that has no allocation
     * rows at all (recorded before this ledger existed): derives covered months from the
     * persisted Payment.month bitmask, reverses oldest-month-first against each month's
     * current StudentFees.amountPaid. Never used when an allocation ledger exists for the
     * payment being refunded. */
    private LegacyReversalResult reverseLegacyByMonthBitmask(Payment payment, Long schoolId, long refundAmountPaise) {
        List<Integer> months = decodeMonthSelection(payment.getMonth());
        long remainingToAllocate = refundAmountPaise;
        StringBuilder monthsReversedMask = new StringBuilder("000000000000");
        List<Integer> monthsActuallyTouched = new ArrayList<>();

        for (Integer month : months) {
            if (remainingToAllocate <= 0) break;
            StudentFees fee = studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonthForUpdate(
                    payment.getStudentId(), schoolId, payment.getSession(), month);
            if (fee == null || !Boolean.TRUE.equals(fee.getPaid()) || fee.getAmountPaid() == null
                    || fee.getAmountPaid().signum() <= 0) {
                continue;
            }

            long rowAmountPaise = fee.getAmountPaid().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            long portion = Math.min(remainingToAllocate, rowAmountPaise);
            long newRowAmountPaise = rowAmountPaise - portion;

            if (newRowAmountPaise <= 0) {
                fee.setPaid(false);
                fee.setAmountPaid(BigDecimal.ZERO);
                if (Boolean.TRUE.equals(fee.getManuallyPaid())) {
                    fee.setManualPaymentReceived(BigDecimal.ZERO);
                }
            } else {
                BigDecimal newAmount = BigDecimal.valueOf(newRowAmountPaise, 2);
                fee.setAmountPaid(newAmount);
                if (Boolean.TRUE.equals(fee.getManuallyPaid())) {
                    fee.setManualPaymentReceived(newAmount);
                }
            }
            studentFeesRepository.save(fee);

            remainingToAllocate -= portion;
            monthsActuallyTouched.add(month);
            monthsReversedMask.setCharAt(month - 1, '1');
        }
        return new LegacyReversalResult(monthsReversedMask.toString(), monthsActuallyTouched);
    }

    /** Decodes a 12-char "010000000000"-style bitmask (bit i = academic month i+1), matching
     * the convention StudentFees.month/Payment.month/PaymentController already use. */
    private List<Integer> decodeMonthSelection(String monthSelectionString) {
        List<Integer> months = new ArrayList<>();
        if (monthSelectionString == null) return months;
        for (int i = 0; i < monthSelectionString.length() && i < 12; i++) {
            if (monthSelectionString.charAt(i) == '1') {
                months.add(i + 1);
            }
        }
        return months;
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
        if (breakdown != null && breakdown.isLineItemBreakdownAvailable()) {
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
        rowIdx = appendFeeRow(feeRows, "Leave Charges",    BigDecimal.valueOf(payment.getAdditionalCharges()), rowIdx);
        rowIdx = appendFeeRow(feeRows, "Late Fees",        BigDecimal.valueOf(payment.getLateFees()),          rowIdx);
               appendFeeRow(feeRows, "Platform Fee",      BigDecimal.valueOf(payment.getPlatformFee()),       rowIdx);

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