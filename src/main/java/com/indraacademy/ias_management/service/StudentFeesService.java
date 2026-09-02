package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.CheckoutQuoteDto;
import com.indraacademy.ias_management.dto.FeeLineItemDto;
import com.indraacademy.ias_management.dto.ManualPaymentRequest;
import com.indraacademy.ias_management.dto.MonthFeeBreakdownDto;
import com.indraacademy.ias_management.dto.StudentFeesAdminUpdateRequest;
import com.indraacademy.ias_management.dto.StudentFeesCreateRequest;
import com.indraacademy.ias_management.entity.LineItemType;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.PaymentStudentFeesAllocation;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentFeesLineItem;
import com.indraacademy.ias_management.entity.StudentOneTimeFeeCharged;
import com.indraacademy.ias_management.repository.AllocationRefundRepository;
import com.indraacademy.ias_management.repository.PaymentStudentFeesAllocationRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeesLineItemRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentOneTimeFeeChargedRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.indraacademy.ias_management.notification.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class StudentFeesService {

    private static final Logger log = LoggerFactory.getLogger(StudentFeesService.class);

    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private FeeCalculationService feeCalculationService;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StudentOneTimeFeeChargedRepository studentOneTimeFeeChargedRepository;
    @Autowired private StudentFeesLineItemRepository studentFeesLineItemRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentStudentFeesAllocationRepository paymentAllocationRepository;
    @Autowired private AllocationRefundRepository allocationRefundRepository;
    @Autowired private BusinessNotificationService businessNotifications;

    private static final Set<String> VALID_MANUAL_PAYMENT_MODES = Set.of("CASH", "CHEQUE", "BANK_TRANSFER", "UPI", "OTHER");

    private String getAcademicYear(LocalDate date) {
        int startMonth = schoolRepository.findById(securityUtil.getSchoolId())
                .map(s -> s.getAcademicYearStartMonth()).orElse(4);
        int year = date.getYear();
        return (date.getMonthValue() >= startMonth)
                ? year + "-" + (year + 1)
                : (year - 1) + "-" + year;
    }

    @Transactional(readOnly = true)
    public List<StudentFees> getStudentFees(String studentId, String year) {
        if (studentId == null || studentId.trim().isEmpty() || year == null || year.trim().isEmpty()) {
            log.warn("Attempted to get student fees with null/empty student ID or year.");
            return Collections.emptyList();
        }
        log.info("Fetching student fees for ID: {} and Year: {}", studentId, year);
        try {
            List<StudentFees> fees = studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(studentId, securityUtil.getSchoolId(), year);
            fees.forEach(fee -> fee.setPaymentProvenance(computePaymentProvenance(fee.getId())));
            return fees;
        } catch (DataAccessException e) {
            log.error("Data access error fetching fees for student ID: {}", studentId, e);
            throw new RuntimeException("Could not retrieve student fees due to data access issue.", e);
        }
    }

    /**
     * The true funding source(s) for a StudentFees row, read directly from the allocation
     * ledger rather than the coarse manuallyPaid boolean — see StudentFees.paymentProvenance's
     * Javadoc. Only allocations with a net-positive contribution (gross minus any refunds
     * reversed specifically against that allocation) count; a fully-refunded allocation no
     * longer "funds" the row. Returns null when nothing is currently net-allocated (unpaid),
     * the exact payment's manualPaymentMode / "RAZORPAY" when every contributing allocation
     * traces back to a single source, or "MIXED" when genuinely more than one distinct source
     * still has net-positive money in this row.
     */
    private String computePaymentProvenance(Long studentFeesId) {
        List<PaymentStudentFeesAllocation> allocations = paymentAllocationRepository.findByStudentFeesId(studentFeesId);
        if (allocations.isEmpty()) {
            return null;
        }
        Set<String> sources = new java.util.LinkedHashSet<>();
        for (PaymentStudentFeesAllocation allocation : allocations) {
            long refunded = allocationRefundRepository.sumAmountPaiseByAllocationId(allocation.getId());
            if (allocation.getAmountPaise() - refunded <= 0) {
                continue;
            }
            paymentRepository.findById(allocation.getPaymentId()).ifPresent(payment -> {
                String mode = payment.getManualPaymentMode();
                sources.add((mode != null && !mode.isBlank()) ? mode : "RAZORPAY");
            });
        }
        if (sources.isEmpty()) {
            return null;
        }
        return sources.size() == 1 ? sources.iterator().next() : "MIXED";
    }

    /**
     * Generic "edit fee record" admin action — deliberately restricted to non-financial
     * fields (className/classId/takesBus/distance) via {@link StudentFeesAdminUpdateRequest}.
     * paid/amountPaid/manuallyPaid/manualPaymentReceived and every snapshot field are never
     * accepted here, regardless of what a caller sends — they are exclusively derived from
     * the payment allocation ledger (markFeesAsPaid / PaymentService.
     * recomputeStudentFeesNetState) or the snapshot generation pipeline. This is what makes
     * it structurally impossible for this endpoint (or any client calling it) to mark a
     * month paid or fabricate a charge, closing the bypass the raw entity-accepting version
     * of this method used to allow.
     */
    @Transactional
    public StudentFees updateStudentFees(StudentFeesAdminUpdateRequest request) {
        if (request == null || request.getId() == null) {
            log.warn("Attempted to update student fees with null request or missing ID.");
            throw new IllegalArgumentException("id must be provided for update.");
        }
        log.info("Updating student fees (non-financial fields only) for ID: {}", request.getId());

        try {
            Long schoolId = securityUtil.getSchoolId();
            StudentFees existing = studentFeesRepository.findById(request.getId()).orElse(null);
            if (existing == null) {
                throw new java.util.NoSuchElementException("StudentFees record not found.");
            }
            if (!schoolId.equals(existing.getSchoolId())) {
                throw new SecurityException("Access denied: fee record does not belong to your school.");
            }

            String oldValue = objectMapper.writeValueAsString(existing);

            if (request.getClassName() != null) existing.setClassName(request.getClassName());
            if (request.getClassId() != null) existing.setClassId(request.getClassId());
            if (request.getTakesBus() != null) existing.setTakesBus(request.getTakesBus());
            if (request.getDistance() != null) existing.setDistance(request.getDistance());

            StudentFees savedFees = studentFeesRepository.save(existing);

            auditService.logUpdate(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "UPDATE_STUDENT_FEES_MANUAL",
                    "StudentFees",
                    request.getId().toString(),
                    oldValue,
                    objectMapper.writeValueAsString(savedFees),
                    "SYSTEM"
            );

            return savedFees;
        } catch (DataAccessException e) {
            log.error("Data access error during student fees update for ID: {}", request.getId(), e);
            throw new RuntimeException("Could not update student fees due to data access issue.", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /** Rounding-only tolerance for treating a StudentFees row as "fully paid" once its net
     * allocation reaches its computed due — absorbs last-cent late-fee-timing drift between
     * checkout display and verification time, matching the same-purpose tolerance
     * PaymentController already uses for checkout-quote matching. Deliberately NOT the old
     * 90%-of-total shortcut: with real per-month allocations, a month is either actually
     * covered (within a cent) or it isn't — there is no longer a reason to approximate. */
    private static final long ROW_FULLY_PAID_TOLERANCE_PAISE = 100L; // ₹1

    /**
     * Allocates a persisted Payment against the StudentFees rows its month selection covers,
     * and (via recomputeStudentFeesNetState-equivalent logic below) derives paid/amountPaid/
     * manuallyPaid/manualPaymentReceived on each touched row from the allocation ledger.
     * Whether this payment counts as "manual" for that derivation comes entirely from
     * {@code payment.getManualPaymentMode()} (set by recordManualPayment, left null by the
     * Razorpay path) — there is no separate manuallyPaid parameter to keep in sync with it.
     */
    @Transactional
    public void markFeesAsPaid(Payment payment) {
        if (payment == null || payment.getStudentId() == null || payment.getSession() == null || payment.getMonth() == null || payment.getMonth().length() != 12) {
            log.error("Invalid Payment object provided for marking fees as paid.");
            throw new IllegalArgumentException("Payment object must contain valid studentId, session, and a 12-char month string.");
        }
        if (payment.getId() == null) {
            // Allocation rows FK to payment.id — the caller must persist the Payment first
            // (both RazorpayService.verifyPayment and recordManualPayment already do this).
            log.error("markFeesAsPaid called with an unpersisted Payment (no id) for student {}.", payment.getStudentId());
            throw new IllegalStateException("Payment must be persisted (have an id) before fees can be allocated against it.");
        }

        String studentId = payment.getStudentId();
        String session = payment.getSession();
        String selectedMonths = payment.getMonth();
        Long schoolId = securityUtil.getSchoolId();
        log.info("Allocating payment {} to fees for student ID: {} for session: {}", payment.getId(), studentId, session);

        // Pass 1: for each selected month, lock its StudentFees row (serializes against any
        // other concurrent payment or refund touching the same row) and compute what it
        // still needs — its full computed due, minus whatever is already net-allocated to it
        // from earlier payments (allocations minus any refunds already reversed against
        // them). School fee due itself comes from the row's own stored snapshot whenever
        // trustworthy (FeeCalculationService.resolveSchoolFeeDue), falling back to a live
        // rule lookup only when untrustworthy; a month whose amount genuinely can't be
        // determined either way refuses the whole payment rather than silently skipping it.
        record PendingMonthAllocation(StudentFees studentFees, long dueForMonthPaise, long alreadyNetAllocatedPaise) {}
        List<PendingMonthAllocation> pending = new java.util.ArrayList<>();
        boolean firstMonth = true;
        for (int i = 0; i < 12; i++) {
            if (selectedMonths.charAt(i) == '1') {
                int monthNumber = i + 1;

                try {
                    StudentFees studentFees = studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonthForUpdate(
                            studentId, schoolId, session, monthNumber);

                    if (studentFees != null) {
                        Optional<BigDecimal> schoolFeeDue = feeCalculationService.resolveSchoolFeeDue(studentFees, schoolId, session);
                        if (schoolFeeDue.isEmpty()) {
                            log.error("Refusing to mark fees paid for student {}: month {} of session {} has no trustworthy "
                                            + "snapshot and no dynamic rules to fall back on — the amount is genuinely unknown.",
                                    studentId, monthNumber, session);
                            throw new IllegalStateException(
                                    "Cannot determine the fee due for student " + studentId + " month " + monthNumber
                                            + " — refusing to process payment without a valid fee determination.");
                        }

                        double totalAmount = schoolFeeDue.get().doubleValue();
                        if (firstMonth) {
                            totalAmount += payment.getAdditionalCharges();
                            firstMonth = false;
                        }
                        totalAmount += calculateLateFees(monthNumber);
                        long dueForMonthPaise = Math.round(totalAmount * 100.0);

                        long alreadyAllocated = paymentAllocationRepository.sumAmountPaiseByStudentFeesId(studentFees.getId());
                        long alreadyRefunded = allocationRefundRepository.sumAmountPaiseByStudentFeesId(studentFees.getId());
                        long alreadyNetAllocatedPaise = alreadyAllocated - alreadyRefunded;

                        pending.add(new PendingMonthAllocation(studentFees, dueForMonthPaise, alreadyNetAllocatedPaise));
                    } else {
                        log.error("No StudentFees record found for student {}, session {}, month {}. Cannot allocate.", studentId, session, monthNumber);
                    }
                } catch (DataAccessException e) {
                    log.error("Data access error computing fees for student {} month {}.", studentId, monthNumber, e);
                    throw new RuntimeException("Failed to compute fees for month " + monthNumber + " due to data access issue.", e);
                } catch (IllegalStateException e) {
                    throw e; // the "cannot determine fee" refusal above — propagate as-is, don't wrap
                } catch (Exception e) {
                    log.error("Unexpected error computing fees for student {} month {}.", studentId, monthNumber, e);
                    throw new RuntimeException("Unexpected error during fee computation for month " + monthNumber, e);
                }
            }
        }

        if (pending.isEmpty()) {
            log.error("Refusing payment {} for student {}: none of the selected months resolved to a StudentFees row.",
                    payment.getId(), studentId);
            throw new IllegalStateException(
                    "No StudentFees records found for any selected month — refusing to process payment without a valid target.");
        }

        boolean anyRemainingNeed = pending.stream().anyMatch(p -> p.dueForMonthPaise() - p.alreadyNetAllocatedPaise() > 0);
        if (!anyRemainingNeed) {
            log.error("Refusing payment {} for student {}: every selected month is already fully paid — nothing to allocate.",
                    payment.getId(), studentId);
            throw new IllegalStateException(
                    "All selected months are already fully paid — refusing to allocate this payment with no target.");
        }

        // Pass 2: distribute the payment across the selected months, in month order, up to
        // each month's remaining need — never more. Any amount left over once every month's
        // need is satisfied (an intentional/accidental overpayment) is credited to the last
        // month that received an allocation, so the full payment amount is always accounted
        // for by allocation rows (SUM(allocations for this payment) == payment.amountPaid).
        long[] allocatedPaise = new long[pending.size()];
        long remainingPool = payment.getAmount();
        int lastAllocatedIndex = -1;
        for (int idx = 0; idx < pending.size() && remainingPool > 0; idx++) {
            PendingMonthAllocation p = pending.get(idx);
            long remainingNeed = Math.max(0, p.dueForMonthPaise() - p.alreadyNetAllocatedPaise());
            long portion = Math.min(remainingPool, remainingNeed);
            if (portion > 0) {
                allocatedPaise[idx] = portion;
                remainingPool -= portion;
                lastAllocatedIndex = idx;
            }
        }
        if (remainingPool > 0 && lastAllocatedIndex >= 0) {
            allocatedPaise[lastAllocatedIndex] += remainingPool;
        }

        for (int idx = 0; idx < pending.size(); idx++) {
            if (allocatedPaise[idx] <= 0) continue;
            PendingMonthAllocation p = pending.get(idx);
            StudentFees studentFees = p.studentFees();

            PaymentStudentFeesAllocation allocation = new PaymentStudentFeesAllocation();
            allocation.setPaymentId(payment.getId());
            allocation.setStudentFeesId(studentFees.getId());
            allocation.setSchoolId(schoolId);
            allocation.setStudentId(studentId);
            allocation.setSession(session);
            allocation.setMonth(studentFees.getMonth());
            allocation.setAmountPaise(allocatedPaise[idx]);
            paymentAllocationRepository.save(allocation);

            long newNetAllocatedPaise = p.alreadyNetAllocatedPaise() + allocatedPaise[idx];
            BigDecimal newAmountPaid = BigDecimal.valueOf(newNetAllocatedPaise, 2);
            boolean fullyPaid = newNetAllocatedPaise >= (p.dueForMonthPaise() - ROW_FULLY_PAID_TOLERANCE_PAISE);

            // manuallyPaid/manualPaymentReceived are derived from the ledger (the net amount
            // specifically contributed by payments with a manualPaymentMode set), the same
            // way PaymentService.recomputeStudentFeesNetState derives them after a refund —
            // one shared definition of "manually paid," not a separately-tracked flag.
            long grossManualAllocated = paymentAllocationRepository.sumManualAmountPaiseByStudentFeesId(studentFees.getId());
            long grossManualReversed = allocationRefundRepository.sumManualReversedAmountPaiseByStudentFeesId(studentFees.getId());
            long netManualPaise = Math.max(0, grossManualAllocated - grossManualReversed);

            studentFees.setPaid(fullyPaid);
            studentFees.setManuallyPaid(netManualPaise > 0);
            studentFees.setManualPaymentReceived(netManualPaise > 0 ? BigDecimal.valueOf(netManualPaise, 2) : BigDecimal.ZERO);
            studentFees.setAmountPaid(newAmountPaid);
            studentFeesRepository.save(studentFees);

            log.info("Allocated {} paise from payment {} to student {} month {}. Net allocated: {} paise (due {} paise) — fullyPaid={}.",
                    allocatedPaise[idx], payment.getId(), studentId, studentFees.getMonth(),
                    newNetAllocatedPaise, p.dueForMonthPaise(), fullyPaid);
        }

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "MARK_FEES_AS_PAID",
                "StudentFees",
                payment.getStudentId() + "_" + payment.getSession(),
                null,
                "Months: " + payment.getMonth() + ", PaymentId: " + payment.getPaymentId(),
                "SYSTEM"
        );
    }

    /**
     * Admin-recorded manual payment (cash/cheque/UPI/bank transfer) — backend-authoritative
     * counterpart to Razorpay checkout. The admin supplies only what they actually observed
     * (student, months, amount received, mode, reference); the amount owed for the selected
     * months is re-derived server-side via markFeesAsPaid, which itself resolves each
     * month's due amount from its stored snapshot (FeeCalculationService.resolveSchoolFeeDue
     * — the same snapshot-first path Razorpay/reminders use), refuses any month whose amount
     * is unresolved, and refuses a payment that doesn't cover the computed total. Payment
     * persistence and the StudentFees row updates happen in one transaction: if
     * markFeesAsPaid rejects the payment, the whole thing — including the Payment row itself
     * — rolls back, so a rejected manual payment never leaves a half-recorded trace.
     */
    @Transactional
    public Payment recordManualPayment(ManualPaymentRequest request, String ipAddress) {
        if (request == null
                || request.getStudentId() == null || request.getStudentId().trim().isEmpty()
                || request.getSession() == null || request.getSession().trim().isEmpty()
                || request.getMonthSelectionString() == null || request.getMonthSelectionString().length() != 12
                || request.getAmountReceived() == null || request.getAmountReceived().signum() <= 0
                || request.getPaymentMode() == null || request.getPaymentMode().trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "studentId, session, a 12-char monthSelectionString, a positive amountReceived, and paymentMode are required.");
        }

        String paymentMode = request.getPaymentMode().trim().toUpperCase();
        if (!VALID_MANUAL_PAYMENT_MODES.contains(paymentMode)) {
            throw new IllegalArgumentException("paymentMode must be one of " + VALID_MANUAL_PAYMENT_MODES + ".");
        }

        Long schoolId = securityUtil.getSchoolId();
        String studentId = request.getStudentId();
        String session = request.getSession();
        String referenceNumber = request.getReferenceNumber() != null ? request.getReferenceNumber().trim() : null;
        if (referenceNumber != null && !referenceNumber.isEmpty()
                && paymentRepository.existsByManualReferenceNumberAndSchoolId(referenceNumber, schoolId)) {
            log.warn("Rejected manual payment for student {}: reference number '{}' already recorded for school {}.",
                    studentId, referenceNumber, schoolId);
            throw new IllegalStateException("A payment with reference number '" + referenceNumber + "' has already been recorded.");
        }

        // Best-effort legacy 5-bucket display fields, sourced from each selected month's real
        // stored snapshot — never fabricated. These columns are display-only fallbacks (see
        // PaymentController.createOrder for the established precedent of the same pattern);
        // the authoritative amount-owed and paid/unpaid state come from markFeesAsPaid's own
        // snapshot resolution below, not from these sums.
        //
        // Uses the SAME locked (ForUpdate) read markFeesAsPaid's Pass 1 uses below, not the
        // plain unlocked one — a race-condition fix. Hibernate's persistence-context identity
        // map returns the same managed Java instance for both reads of the same row within
        // this one transaction; an earlier UNLOCKED read here would seed that instance with
        // whatever was committed at that moment, and markFeesAsPaid's later locked re-fetch
        // would silently reuse the stale copy instead of refreshing it — even though the lock
        // itself was correctly acquired. A concurrent recalculation committed between the two
        // reads would then be clobbered when this method's own save() writes back every field
        // of the (stale) entity, not just the ones this method intended to change. Acquiring
        // the lock here, at the first touch of the row, means the entity is fresh for the
        // rest of this transaction and no such staleness is possible.
        BigDecimal busFeeBucket = BigDecimal.ZERO;
        BigDecimal schoolFeeBucket = BigDecimal.ZERO;
        int lateFeeBucket = 0;
        String months = request.getMonthSelectionString();
        List<Integer> monthsWithNoRow = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            if (months.charAt(i) == '1') {
                int monthNumber = i + 1;
                StudentFees fee = studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonthForUpdate(studentId, schoolId, session, monthNumber);
                if (fee == null) {
                    // No StudentFees row under THIS admin's own schoolId — either a bad month
                    // selection or (critically) a studentId that doesn't actually belong to
                    // this school. markFeesAsPaid's pass 1 would otherwise silently skip a
                    // missing row rather than throw, which — if every selected month were
                    // missing — would let this method "succeed" with zero months resolved,
                    // still saving a Payment and logging it as recorded. Refuse explicitly
                    // instead of relying on that silent-skip.
                    monthsWithNoRow.add(monthNumber);
                    continue;
                }
                if (fee.getBusFeeDue() != null) {
                    busFeeBucket = busFeeBucket.add(fee.getBusFeeDue());
                }
                if (fee.getBaseAmountDue() != null) {
                    BigDecimal discount = fee.getDiscountAmount() != null ? fee.getDiscountAmount() : BigDecimal.ZERO;
                    schoolFeeBucket = schoolFeeBucket.add(fee.getBaseAmountDue().subtract(discount));
                }
                lateFeeBucket += calculateLateFees(monthNumber);
            }
        }
        if (!monthsWithNoRow.isEmpty()) {
            log.error("Refusing manual payment for student {}: no StudentFees row under school {} for month(s) {} — "
                            + "either an invalid month selection or the student does not belong to this school.",
                    studentId, schoolId, monthsWithNoRow);
            throw new IllegalStateException(
                    "Cannot determine the fee due for student " + studentId + " month(s) " + monthsWithNoRow
                            + " — refusing to process payment without a valid fee determination.");
        }

        int amountReceivedPaise = request.getAmountReceived().movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
        int additionalCharges = request.getAdditionalCharges() != null ? request.getAdditionalCharges() : 0;

        Payment payment = new Payment(
                studentId,
                request.getStudentName(),
                request.getClassName(),
                session,
                months,
                amountReceivedPaise,
                "MANUAL_" + UUID.randomUUID().toString().substring(0, 15),
                "Manual payment",
                LocalDateTime.now(),
                "success",
                busFeeBucket.setScale(0, RoundingMode.HALF_UP).intValueExact(),
                schoolFeeBucket.setScale(0, RoundingMode.HALF_UP).intValueExact(),
                0, 0, 0, 0,
                true,
                amountReceivedPaise,
                additionalCharges,
                lateFeeBucket
        );
        payment.setSchoolId(schoolId);
        payment.setRazorpaySignature("MANUAL-PAYMENT");
        payment.setManualPaymentMode(paymentMode);
        payment.setManualReferenceNumber((referenceNumber != null && !referenceNumber.isEmpty()) ? referenceNumber : null);

        try {
            paymentRepository.save(payment);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // A concurrent request slipped past the existsByManualReferenceNumberAndSchoolId
            // check above between the check and this save — the DB unique index on
            // payment(school_id, manual_reference_number) (added this phase) is the real
            // backstop for that race. Reported the same way the early check reports it.
            log.warn("Duplicate manual-payment reference race detected for student {}: reference '{}' already recorded for school {}.",
                    studentId, referenceNumber, schoolId, e);
            throw new IllegalStateException("A payment with reference number '" + referenceNumber + "' has already been recorded.");
        }

        // Throws (unresolved month / payment short of the computed total) roll back this
        // whole transaction, including the Payment save above — never a half-recorded
        // manual payment with StudentFees left untouched. payment.manualPaymentMode is
        // already set above, which is what makes the touched rows' manuallyPaid/
        // manualPaymentReceived correctly derive as "manual" inside markFeesAsPaid.
        markFeesAsPaid(payment);

        businessNotifications.studentAndParents(schoolId, studentId,
                NotificationAudienceType.STUDENT_WITH_FEE_PARENTS,
                NotificationEventCode.PAYMENT_SUCCESS, NotificationCategory.FEES_PAYMENTS,
                "Payment Recorded", "Your fee payment has been recorded successfully.",
                "Payment", String.valueOf(payment.getId()), "/dashboard/payment-history",
                securityUtil.getUsername(), "payment-success:" + payment.getPaymentId(),
                Set.of(ExternalDeliveryChannel.PUSH));

        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("studentId", studentId);
            details.put("schoolId", schoolId);
            details.put("session", session);
            details.put("monthSelectionString", months);
            details.put("amountReceivedPaise", amountReceivedPaise);
            details.put("paymentMode", paymentMode);
            details.put("referenceNumber", referenceNumber);
            details.put("admin", securityUtil.getUsername());
            details.put("timestamp", LocalDateTime.now().toString());
            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "RECORD_MANUAL_PAYMENT",
                    "Payment",
                    payment.getPaymentId(),
                    null,
                    objectMapper.writeValueAsString(details),
                    ipAddress != null ? ipAddress : "SYSTEM"
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        log.info("Manual payment recorded for student {} (paymentId={}), mode={}, reference={}.",
                studentId, payment.getPaymentId(), paymentMode, referenceNumber);
        return payment;
    }

    private int calculateLateFees(int academicFeeMonth) {
        if (academicFeeMonth < 1 || academicFeeMonth > 12) {
            log.warn("Invalid academicFeeMonth: {}. Returning 0 late fees.", academicFeeMonth);
            return 0;
        }

        LocalDate today = LocalDate.now();
        int currentCalendarMonth = today.getMonthValue();
        int academicCurrentMonth = getAcademicMonth(currentCalendarMonth);

        int monthDifference = academicCurrentMonth - academicFeeMonth;

        if (monthDifference <= 0) return 0;

        // Using the same lateFeePerDay logic as in the Angular component
        int[] lateFeePerDay = {12, 15, 18, 21};

        if (monthDifference >= 9) {
            return 30 * lateFeePerDay[3];
        } else if (monthDifference >= 6) {
            return 30 * lateFeePerDay[2];
        } else if (monthDifference >= 3) {
            return 30 * lateFeePerDay[1];
        } else {
            return 30 * lateFeePerDay[0];
        }
    }

    private int getAcademicMonth(int calendarMonth) {
        int startMonth = schoolRepository.findById(securityUtil.getSchoolId())
                .map(s -> s.getAcademicYearStartMonth()).orElse(4);
        return ((calendarMonth - startMonth + 12) % 12) + 1;
    }

    /** Payment-gateway charge, computed at checkout time — never part of the student's
     * original school debt (see CheckoutQuoteDto). Matches the rate the frontend used to
     * compute client-side; now backend-authoritative and the single source of truth. */
    private static final BigDecimal PLATFORM_FEE_RATE = BigDecimal.valueOf(0.015);

    /**
     * Backend-authoritative checkout quote: REMAINING school fee due (each requested month's
     * StudentFees snapshot due, via FeeCalculationService.resolveSchoolFeeDue, minus that same
     * row's ledger-derived net amountPaid — see below) + REMAINING late fee + platform fee
     * (PLATFORM_FEE_RATE, applied to the two remaining figures) = totalAmount.
     * <p>
     * Fixed defect (found during a fee-reminder investigation, but this method — not just the
     * reminder — was the actual bug): this used to sum each month's full original due amount
     * with no regard for amountPaid, so a month with a partial/since-refunded payment (paid
     * stays false until amountPaid covers the full due amount — see
     * PaymentService.recomputeStudentFeesNetState) was quoted at its FULL original amount
     * again, on both the checkout screen and the actual Razorpay order-creation path
     * (PaymentController.createOrder) — a real double-charge risk, not just a display bug.
     * amountPaid is a single undifferentiated pool (the ledger doesn't track which of school
     * fee / late fee / platform fee a given rupee paid down), so it's applied here in that
     * same order: first against schoolFeeDue, any excess then against the late fee, matching
     * recomputeStudentFeesNetState's own "amountPaid vs resolveSchoolFeeDue" basis for the
     * paid flag so the two never disagree about what's been credited to the school-fee portion.
     * Platform fee, by contrast, is never reduced by a prior payment — it's a fresh
     * payment-time charge on whatever principal is still genuinely outstanding.
     * <p>
     * A requested month with no StudentFees row, or whose amount can't be confidently
     * resolved, is added to unresolvedMonths and excluded from every total — the caller
     * must treat a non-empty unresolvedMonths as "this quote is incomplete," never silently
     * charge for only the resolved months as if that were the full amount owed.
     */
    @Transactional(readOnly = true)
    public CheckoutQuoteDto computeCheckoutQuote(String studentId, String session, List<Integer> months) {
        Long schoolId = securityUtil.getSchoolId();

        BigDecimal schoolFeeDue = BigDecimal.ZERO;
        BigDecimal lateFee = BigDecimal.ZERO;
        List<Integer> unresolvedMonths = new java.util.ArrayList<>();

        for (Integer month : months) {
            if (month == null || month < 1 || month > 12) {
                unresolvedMonths.add(month);
                continue;
            }
            StudentFees fee = studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth(studentId, schoolId, session, month);
            if (fee == null) {
                log.warn("checkout-quote: no StudentFees row for student {} session {} month {} — marking unresolved.",
                        studentId, session, month);
                unresolvedMonths.add(month);
                continue;
            }
            Optional<BigDecimal> resolved = feeCalculationService.resolveSchoolFeeDue(fee, schoolId, session);
            if (resolved.isEmpty()) {
                unresolvedMonths.add(month);
                continue;
            }
            BigDecimal grossDue = resolved.get();
            BigDecimal netPaid = fee.getAmountPaid() != null ? fee.getAmountPaid() : BigDecimal.ZERO;

            BigDecimal remainingSchoolFee = grossDue.subtract(netPaid).max(BigDecimal.ZERO);
            BigDecimal excessBeyondSchoolFee = netPaid.subtract(grossDue).max(BigDecimal.ZERO);

            BigDecimal grossLateFee = BigDecimal.valueOf(calculateLateFees(month));
            BigDecimal remainingLateFee = grossLateFee.subtract(excessBeyondSchoolFee).max(BigDecimal.ZERO);

            schoolFeeDue = schoolFeeDue.add(remainingSchoolFee);
            lateFee = lateFee.add(remainingLateFee);
        }

        BigDecimal preFeeSubtotal = schoolFeeDue.add(lateFee);
        BigDecimal platformFee = preFeeSubtotal.multiply(PLATFORM_FEE_RATE)
                .setScale(0, java.math.RoundingMode.CEILING); // ceiling, matching the frontend's prior Math.ceil
        BigDecimal totalAmount = preFeeSubtotal.add(platformFee);

        CheckoutQuoteDto dto = new CheckoutQuoteDto();
        dto.setStudentId(studentId);
        dto.setSession(session);
        dto.setMonths(months);
        dto.setSchoolFeeDue(schoolFeeDue);
        dto.setLateFee(lateFee);
        dto.setPlatformFee(platformFee);
        dto.setTotalAmount(totalAmount);
        dto.setUnresolvedMonths(unresolvedMonths);
        return dto;
    }

    @Transactional(readOnly = true)
    public Optional<StudentFees> getStudentFee(String studentId, String year, Integer month) {
        if (studentId == null || studentId.trim().isEmpty() || year == null || year.trim().isEmpty() || month == null || month < 1 || month > 12) {
            log.warn("Attempted to get student fee with null/empty/invalid parameters. ID: {}, Year: {}, Month: {}", studentId, year, month);
            return Optional.empty();
        }
        log.info("Fetching student fee for ID: {}, Year: {}, Month: {}", studentId, year, month);
        try {
            return Optional.ofNullable(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth(studentId, securityUtil.getSchoolId(), year, month));
        } catch (DataAccessException e) {
            log.error("Data access error fetching single fee record for student ID: {}", studentId, e);
            throw new RuntimeException("Could not retrieve student fee record due to data access issue.", e);
        }
    }

    /**
     * Backend-authoritative per-fee-head breakdown for a single StudentFees month, backing
     * the parent-facing receipt/breakdown panel (Phase 3 — frontend & read-model alignment).
     * Never fabricates a breakdown: lineItems only ever comes from real, persisted
     * StudentFeesLineItem rows. Empty lineItems (a historical row generated before line
     * items existed, or one with no active rule that month) sets
     * lineItemBreakdownAvailable=false so the caller shows "breakdown unavailable" instead
     * of inventing per-fee-head components — schoolFeeDue (resolveSchoolFeeDue, the exact
     * figure the checkout quote and reminders use) still carries the trusted total when the
     * row's own snapshot is trustworthy, and is null only when the total itself is unknown.
     */
    @Transactional(readOnly = true)
    public Optional<MonthFeeBreakdownDto> getMonthFeeBreakdown(String studentId, String year, Integer month) {
        if (studentId == null || studentId.trim().isEmpty() || year == null || year.trim().isEmpty() || month == null || month < 1 || month > 12) {
            log.warn("Attempted to get month fee breakdown with null/empty/invalid parameters. ID: {}, Year: {}, Month: {}", studentId, year, month);
            return Optional.empty();
        }
        Long schoolId = securityUtil.getSchoolId();
        StudentFees fee = studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth(studentId, schoolId, year, month);
        if (fee == null) {
            return Optional.empty();
        }

        List<StudentFeesLineItem> rows = studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(fee.getId());
        List<FeeLineItemDto> lineItems = rows.stream().map(li -> {
            FeeLineItemDto dto = new FeeLineItemDto();
            dto.setLineItemType(li.getLineItemType() != null ? li.getLineItemType().name() : null);
            dto.setFeeHeadCode(li.getFeeHeadCode());
            dto.setFeeHeadName(li.getFeeHeadName());
            dto.setDiscountConfigType(li.getDiscountConfigType());
            dto.setGrossAmount(BigDecimal.valueOf(li.getGrossAmountPaise(), 2));
            dto.setDiscountAmount(BigDecimal.valueOf(li.getDiscountAmountPaise(), 2));
            dto.setNetAmount(BigDecimal.valueOf(li.getNetAmountPaise(), 2));
            return dto;
        }).collect(java.util.stream.Collectors.toList());

        MonthFeeBreakdownDto dto = new MonthFeeBreakdownDto();
        dto.setStudentId(studentId);
        dto.setSession(year);
        dto.setMonth(month);
        dto.setLineItems(lineItems);
        dto.setLineItemBreakdownAvailable(!lineItems.isEmpty());
        dto.setSchoolFeeDue(feeCalculationService.resolveSchoolFeeDue(fee, schoolId, year).orElse(null));
        return Optional.of(dto);
    }

    /**
     * Ad-hoc single-row creation (e.g. an admin backfilling one missing month) — restricted
     * to {@link StudentFeesCreateRequest}'s identity/non-financial fields. The row's
     * financial snapshot is always computed server-side via the same
     * FeeCalculationService.computeMonthSnapshot pipeline bulk generation uses, never
     * accepted from the client; the row always starts unpaid.
     */
    @Transactional
    public StudentFees createStudentFees(StudentFeesCreateRequest request) {
        if (request == null || request.getStudentId() == null || request.getStudentId().isBlank()
                || request.getClassName() == null || request.getClassName().isBlank()
                || request.getYear() == null || request.getYear().isBlank()
                || request.getMonth() == null || request.getMonth() < 1 || request.getMonth() > 12) {
            log.warn("Attempted to create student fees with missing/invalid required fields.");
            throw new IllegalArgumentException("studentId, className, year, and a valid month (1-12) are required.");
        }
        log.info("Creating ad-hoc student fees record for ID: {} Year: {} Month: {}",
                request.getStudentId(), request.getYear(), request.getMonth());

        Long schoolId = securityUtil.getSchoolId();
        if (studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonth(
                request.getStudentId(), schoolId, request.getYear(), request.getMonth()) != null) {
            throw new IllegalStateException("A StudentFees record already exists for this student/year/month.");
        }

        FeeCalculationService.FeeConfigurationStatus configStatus =
                feeCalculationService.validateFeeConfiguration(schoolId, request.getYear(), request.getClassName());
        if (!configStatus.valid()) {
            log.error("Refusing ad-hoc StudentFees creation for {} — schoolId={}, session={}, className={}: {}",
                    request.getStudentId(), schoolId, request.getYear(), request.getClassName(), configStatus.reason());
            throw new IllegalStateException("Cannot create fee record: " + configStatus.reason());
        }

        try {
            boolean takesBus = Boolean.TRUE.equals(request.getTakesBus());
            int schoolStartMonth = schoolRepository.findById(schoolId)
                    .map(s -> s.getAcademicYearStartMonth()).orElse(4);
            int[] sessionYears = feeCalculationService.parseSession(request.getYear());
            LocalDate asOfDate = feeCalculationService.academicMonthStart(
                    request.getMonth(), sessionYears[0], sessionYears[1], schoolStartMonth);
            Set<Long> chargedOneTimeFeeHeadIds = new HashSet<>(
                    studentOneTimeFeeChargedRepository.findFeeHeadIdBySchoolIdAndStudentId(schoolId, request.getStudentId()));

            FeeCalculationService.MonthSnapshot snapshot = feeCalculationService.computeMonthSnapshot(
                    schoolId, request.getYear(), request.getClassName(), request.getStudentId(), request.getMonth(),
                    false, asOfDate, takesBus, request.getDistance(), chargedOneTimeFeeHeadIds);

            StudentFees studentFees = new StudentFees();
            studentFees.setStudentId(request.getStudentId());
            studentFees.setClassName(request.getClassName());
            studentFees.setYear(request.getYear());
            studentFees.setMonth(request.getMonth());
            studentFees.setSchoolId(schoolId);
            studentFees.setTakesBus(takesBus);
            studentFees.setDistance(Objects.requireNonNullElse(request.getDistance(), 0.0));
            studentFees.setPaid(false);
            studentFees.setManuallyPaid(false);
            studentFees.setManualPaymentReceived(null);
            studentFees.setAmountPaid(null);
            studentFees.setBaseAmountDue(snapshot.baseAmountDue());
            studentFees.setBusFeeDue(snapshot.busFeeDue());
            studentFees.setDiscountAmount(snapshot.discountAmount());
            studentFees.setAmountComputedAt(LocalDateTime.now());
            studentFees.setAmountRuleSnapshot(snapshot.ruleSnapshotJson());
            studentFees.setSnapshotStatus(snapshot.status());

            StudentFees saved = studentFeesRepository.save(studentFees);

            for (FeeCalculationService.LineItemSnapshot li : snapshot.lineItems()) {
                StudentFeesLineItem lineItem = new StudentFeesLineItem();
                lineItem.setStudentFeesId(saved.getId());
                lineItem.setSchoolId(schoolId);
                lineItem.setStudentId(request.getStudentId());
                lineItem.setSession(request.getYear());
                lineItem.setMonth(request.getMonth());
                lineItem.setLineItemType(LineItemType.valueOf(li.lineItemType()));
                lineItem.setFeeHeadId(li.feeHeadId());
                lineItem.setFeeHeadCode(li.feeHeadCode());
                lineItem.setFeeHeadName(li.feeHeadName());
                lineItem.setFrequency(li.frequency());
                lineItem.setGrossAmountPaise(li.grossPaise());
                lineItem.setDiscountAmountPaise(li.discountPaise());
                lineItem.setNetAmountPaise(li.netPaise());
                lineItem.setDiscountConfigType(li.discountConfigType());
                studentFeesLineItemRepository.save(lineItem);
            }

            for (Long feeHeadId : snapshot.newlyChargedOneTimeFeeHeadIds()) {
                studentOneTimeFeeChargedRepository.save(new StudentOneTimeFeeCharged(schoolId, request.getStudentId(), feeHeadId));
            }

            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "CREATE_STUDENT_FEES_MANUAL",
                    "StudentFees",
                    saved.getId().toString(),
                    null,
                    "Ad-hoc creation for student " + request.getStudentId() + " year " + request.getYear() + " month " + request.getMonth(),
                    "SYSTEM"
            );

            return saved;
        } catch (DataAccessException e) {
            log.error("Data access error creating student fees for ID: {}", request.getStudentId(), e);
            throw new RuntimeException("Could not create student fees record due to data access issue.", e);
        }
    }

    @Transactional(readOnly = true)
    public List<String> getDistinctYearsByStudentId(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) {
            log.warn("Attempted to get distinct years with null/empty student ID.");
            return Collections.emptyList();
        }
        log.info("Fetching distinct years for student ID: {}", studentId);
        try {
            return studentFeesRepository.findDistinctYearsByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId());
        } catch (DataAccessException e) {
            log.error("Data access error fetching distinct years for student ID: {}", studentId, e);
            throw new RuntimeException("Could not retrieve distinct years due to data access issue.", e);
        }
    }

    @Transactional
    public void updateStudentFeesForClassChange(String studentId, String newClassName) {
        if (studentId == null || studentId.trim().isEmpty() || newClassName == null || newClassName.trim().isEmpty()) {
            log.warn("Attempted to update fees for class change with null/empty parameters. ID: {}, New Class: {}", studentId, newClassName);
            throw new IllegalArgumentException("Student ID and new class name must be provided.");
        }

        String academicYear = getAcademicYear(LocalDate.now());
        log.info("Updating class name for student ID: {} to {} in academic year: {}", studentId, newClassName, academicYear);

        try {
            Long schoolId = securityUtil.getSchoolId();
            List<StudentFees> studentFeesList = studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(studentId, schoolId, academicYear);
            if (studentFeesList.isEmpty()) {
                log.info("No StudentFees records found for student ID: {} in academic year: {}. Skipping update.", studentId, academicYear);
                return;
            }

            int updatedCount = 0;
            for (StudentFees fee : studentFeesList) {
                fee.setClassName(newClassName);
                studentFeesRepository.save(fee);
                updatedCount++;
            }

            if (updatedCount > 0) {
                auditService.log(
                        securityUtil.getUsername(),
                        securityUtil.getRole(),
                        "UPDATE_FEES_CLASS_CHANGE",
                        "StudentFees",
                        studentId + "_" + academicYear,
                        null,
                        "Updated class to " + newClassName +
                                " for " + updatedCount + " months",
                        "SYSTEM"
                );
            }
            log.info("Updated class name for {} StudentFees records for student ID: {}.", updatedCount, studentId);
        } catch (DataAccessException e) {
            log.error("Data access error updating fees for class change for student ID: {}.", studentId, e);
            throw new RuntimeException("Failed to update fees for class change due to data access issue.", e);
        }
    }

    @Transactional
    public void createDefaultStudentFees(String studentId, String className, String year, Boolean takesBus, Double distance, LocalDate joiningDate) {
        if (studentId == null || studentId.trim().isEmpty() || className == null || className.trim().isEmpty() || year == null || year.trim().isEmpty() || takesBus == null) {
            log.warn("Attempted to create default fees with null/empty parameters. ID: {}, Year: {}", studentId, year);
            throw new IllegalArgumentException("Student ID, Class Name, Year, and Takes Bus status must be provided.");
        }

        log.info("Creating fee records for student ID: {} Year: {}, from their joining month onward", studentId, year);

        Long schoolId = securityUtil.getSchoolId();

        // Guards against a double-click on registration or a retried request creating a
        // second full set of rows for the same student/year — the real backstop is the DB
        // unique constraint on (school_id, student_id, year, month), but failing fast here
        // with a clear message is friendlier than surfacing a raw constraint-violation error.
        if (studentFeesRepository.existsByStudentIdAndYearAndSchoolId(studentId, year, schoolId)) {
            log.warn("Refusing to create default fees for student {}: rows already exist for schoolId={}, year={}.",
                    studentId, schoolId, year);
            throw new IllegalStateException(
                    "StudentFees rows already exist for student " + studentId + " for year " + year + ".");
        }

        int schoolStartMonth = schoolRepository.findById(schoolId)
                .map(s -> s.getAcademicYearStartMonth()).orElse(4);

        // Convert joining calendar month to academic month (1 = first month of school year)
        int joiningCalendarMonth = joiningDate.getMonthValue();
        int joinAcademicMonth = ((joiningCalendarMonth - schoolStartMonth + 12) % 12) + 1;
        int endMonth = 12;
        int[] sessionYears = feeCalculationService.parseSession(year);

        // Never persist a fee snapshot when we cannot confidently calculate it — validated
        // BEFORE any row is created, so a class with no fee structure configured never
        // silently produces a full set of frozen ₹0.00 rows for a newly-registered student.
        // Unlike the batch scheduler (which skips-and-logs so one bad class doesn't block
        // every other student), this is a single, synchronous, admin-triggered registration —
        // failing loudly here lets the caller (registration flow) surface a clear, actionable
        // error instead of silently leaving a student with no fee records at all.
        FeeCalculationService.FeeConfigurationStatus configStatus =
                feeCalculationService.validateFeeConfiguration(schoolId, year, className);
        if (!configStatus.valid()) {
            log.error("Refusing to create default fees for student {} — schoolId={}, session={}, className={}: {}",
                    studentId, schoolId, year, className, configStatus.reason());
            throw new IllegalStateException(
                    "Cannot generate fees for student " + studentId + ": " + configStatus.reason());
        }

        Set<Long> chargedOneTimeFeeHeadIds = new HashSet<>(
                studentOneTimeFeeChargedRepository.findFeeHeadIdBySchoolIdAndStudentId(schoolId, studentId));

        try {
            for (int month = joinAcademicMonth; month <= endMonth; month++) {
                boolean isFirstRow = (month == joinAcademicMonth);
                LocalDate asOfDate = feeCalculationService.academicMonthStart(
                        month, sessionYears[0], sessionYears[1], schoolStartMonth);

                FeeCalculationService.MonthSnapshot snapshot = feeCalculationService.computeMonthSnapshot(
                        schoolId, year, className, studentId, month, isFirstRow, asOfDate,
                        takesBus, distance, chargedOneTimeFeeHeadIds);

                StudentFees studentFee = new StudentFees();
                studentFee.setStudentId(studentId);
                studentFee.setClassName(className);
                studentFee.setMonth(month);
                studentFee.setPaid(false);
                studentFee.setTakesBus(takesBus);
                studentFee.setYear(year);
                studentFee.setDistance(Objects.requireNonNullElse(distance, 0.0));
                studentFee.setManuallyPaid(false);
                studentFee.setManualPaymentReceived(null);
                studentFee.setSchoolId(schoolId);
                studentFee.setBaseAmountDue(snapshot.baseAmountDue());
                studentFee.setBusFeeDue(snapshot.busFeeDue());
                studentFee.setDiscountAmount(snapshot.discountAmount());
                studentFee.setAmountComputedAt(LocalDateTime.now());
                studentFee.setAmountRuleSnapshot(snapshot.ruleSnapshotJson());
                studentFee.setSnapshotStatus(snapshot.status());
                studentFeesRepository.save(studentFee);

                for (FeeCalculationService.LineItemSnapshot li : snapshot.lineItems()) {
                    StudentFeesLineItem lineItem = new StudentFeesLineItem();
                    lineItem.setStudentFeesId(studentFee.getId());
                    lineItem.setSchoolId(schoolId);
                    lineItem.setStudentId(studentId);
                    lineItem.setSession(year);
                    lineItem.setMonth(month);
                    lineItem.setLineItemType(LineItemType.valueOf(li.lineItemType()));
                    lineItem.setFeeHeadId(li.feeHeadId());
                    lineItem.setFeeHeadCode(li.feeHeadCode());
                    lineItem.setFeeHeadName(li.feeHeadName());
                    lineItem.setFrequency(li.frequency());
                    lineItem.setGrossAmountPaise(li.grossPaise());
                    lineItem.setDiscountAmountPaise(li.discountPaise());
                    lineItem.setNetAmountPaise(li.netPaise());
                    lineItem.setDiscountConfigType(li.discountConfigType());
                    studentFeesLineItemRepository.save(lineItem);
                }

                for (Long feeHeadId : snapshot.newlyChargedOneTimeFeeHeadIds()) {
                    studentOneTimeFeeChargedRepository.save(
                            new StudentOneTimeFeeCharged(schoolId, studentId, feeHeadId));
                    chargedOneTimeFeeHeadIds.add(feeHeadId);
                }
            }

            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "CREATE_DEFAULT_STUDENT_FEES",
                    "StudentFees",
                    studentId + "_" + year,
                    null,
                    "Created fee records from academic month " + joinAcademicMonth + " through " + endMonth,
                    "SYSTEM"
            );

            log.info("Successfully created default fee records for student ID: {} from academic month {}", studentId, joinAcademicMonth);
        } catch (DataAccessException e) {
            log.error("Data access error creating default student fees for ID: {}.", studentId, e);
            throw new RuntimeException("Failed to create default student fees due to data access issue.", e);
        }
    }

    public void updateStudentBusFees(String studentId, Boolean takesBus, Double distance, Integer effectiveFromMonth) {
        if (studentId == null || studentId.trim().isEmpty() || takesBus == null || effectiveFromMonth == null || effectiveFromMonth < 0 || effectiveFromMonth > 12) {
            log.warn("Attempted to update bus fees with invalid parameters. ID: {}, Month: {}", studentId, effectiveFromMonth);
            throw new IllegalArgumentException("Student ID, Takes Bus status, and a valid Effective Month (0-12) must be provided.");
        }

        if (effectiveFromMonth == 0) {
            log.info("EffectiveFromMonth is 0. Skipping bus fee update for student ID: {}", studentId);
            return;
        }

        String academicYear = getAcademicYear(LocalDate.now());
        log.info("Updating bus fees for student ID: {} starting from academic month {} in year {}", studentId, effectiveFromMonth, academicYear);

        try {
            Long schoolId = securityUtil.getSchoolId();
            List<StudentFees> studentFeesList = studentFeesRepository.findByStudentIdAndSchoolIdAndYearOrderByMonthAsc(studentId, schoolId, academicYear);

            if (studentFeesList.isEmpty()) {
                log.info("No StudentFees records found for student ID: {} in academic year: {}. Skipping bus fees update.", studentId, academicYear);
                return;
            }

            int updatedCount = 0;
            for (StudentFees fee : studentFeesList) {
                if (fee.getMonth() >= effectiveFromMonth) {
                    log.debug("Updating bus fees for month: {}", fee.getMonth());
                    fee.setTakesBus(takesBus);
                    fee.setDistance(Objects.requireNonNullElse(distance, 0.0));
                    studentFeesRepository.save(fee);
                    updatedCount++;
                }
            }

            if (updatedCount > 0) {
                auditService.log(
                        securityUtil.getUsername(),
                        securityUtil.getRole(),
                        "UPDATE_STUDENT_BUS_FEES",
                        "StudentFees",
                        studentId + "_" + academicYear,
                        null,
                        "Updated from month " + effectiveFromMonth +
                                ", TakesBus: " + takesBus,
                        "SYSTEM"
                );
            }

            log.info("Successfully updated bus fees for {} fee records for student ID: {}.", updatedCount, studentId);
        } catch (DataAccessException e) {
            log.error("Data access error updating bus fees for student ID: {}.", studentId, e);
            throw new RuntimeException("Failed to update student bus fees due to data access issue.", e);
        }
    }
}
