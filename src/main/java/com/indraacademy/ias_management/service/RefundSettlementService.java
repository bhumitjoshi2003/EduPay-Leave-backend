package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.RefundRequest;
import com.indraacademy.ias_management.entity.AllocationRefund;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.PaymentStudentFeesAllocation;
import com.indraacademy.ias_management.entity.Refund;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.AllocationRefundRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.PaymentStudentFeesAllocationRepository;
import com.indraacademy.ias_management.repository.RefundRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.notification.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Refund-Integrity Hardening, Phase B — the atomic, concurrency-safe boundary for refund
 * capacity reservation and financial finalization. Extracted into its own {@code @Service} bean
 * (not left as private methods on {@link PaymentService}) for the same reason
 * {@link PaymentSettlementService} was: Spring's transactional proxy only intercepts calls that
 * arrive through the bean reference, never a same-class {@code this.method()} call — so
 * {@link #reserve} and {@link #finalizeSuccessfulRefund} genuinely need to be separate,
 * independently-transactional calls from {@link PaymentService#processRefund}, not just
 * differently-annotated private methods in the same class.
 * <p>
 * The central invariant: {@code payment.refundedAmountPaise} represents refund capacity that
 * has already been reserved or consumed — incremented the moment a refund is accepted
 * (status PENDING), not only once it is confirmed successful. This is what makes two
 * concurrent refund requests against the same payment correctly serialize on the existing
 * {@code PaymentRepository.findByIdForUpdate} pessimistic lock: whichever transaction commits
 * its reservation first raises the ledger total, so the second sees the reduced remaining
 * balance and is rejected before it can ever reach the provider.
 */
@Service
public class RefundSettlementService {

    private static final Logger log = LoggerFactory.getLogger(RefundSettlementService.class);

    /** Matches the pre-existing tolerance/rounding convention in PaymentService's own
     * recomputeStudentFeesNetState (moved here unchanged). */
    private static final long ROW_FULLY_PAID_TOLERANCE_PAISE = 100L;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESS = "success"; // unchanged sentinel from pre-Phase-B code
    public static final String STATUS_FAILED = "FAILED";

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private PaymentStudentFeesAllocationRepository paymentAllocationRepository;
    @Autowired private AllocationRefundRepository allocationRefundRepository;
    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private FeeCalculationService feeCalculationService;
    @Autowired private BusinessNotificationService businessNotifications;
    @Autowired private AuditService auditService;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @PersistenceContext private EntityManager entityManager;

    public enum ReservationOutcome { RESERVED, ALREADY_RESERVED, REJECTED }

    public record ReservationResult(ReservationOutcome outcome, String message, Refund refund, Payment payment) {
        static ReservationResult reserved(Refund refund, Payment payment) {
            return new ReservationResult(ReservationOutcome.RESERVED, "Reservation created.", refund, payment);
        }
        static ReservationResult alreadyReserved(Refund refund) {
            return new ReservationResult(ReservationOutcome.ALREADY_RESERVED, "Existing refund found for this idempotency key.", refund, null);
        }
        static ReservationResult rejected(String message) {
            return new ReservationResult(ReservationOutcome.REJECTED, message, null, null);
        }
    }

    /**
     * Transaction A ("Reserve"): locks the Payment row (the pre-existing
     * {@code findByIdForUpdate} finder, already used by the refund flow before Phase B — no new
     * locking method was needed), validates remaining refundable capacity against
     * {@code payment.refundedAmountPaise} (NOT a re-sum of {@code refund.amount_paise} rows —
     * that field is now the authoritative reserved-or-consumed total), and — still under the
     * same lock, in the same transaction — creates a PENDING Refund row, assigns it a stable,
     * server-generated {@code providerIdempotencyKey}, and increments the payment's reserved
     * total. Commits before returning, releasing the lock — the caller must not call Razorpay
     * until this has returned {@link ReservationOutcome#RESERVED}.
     * <p>
     * A client-supplied {@code idempotencyKey} that already names a Refund row (whatever its
     * status) short-circuits here with {@link ReservationOutcome#ALREADY_RESERVED} instead of
     * reserving again — the caller decides what that means (reuse a still-PENDING reservation,
     * report an already-terminal outcome) since this method only reports what it found.
     */
    @Transactional
    public ReservationResult reserve(Long paymentId, RefundRequest request, Long schoolId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null || !schoolId.equals(payment.getSchoolId())) {
            log.warn("Refund reservation rejected: payment not found or does not belong to school. paymentId={} schoolId={}", paymentId, schoolId);
            return ReservationResult.rejected("Payment not found.");
        }

        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Refund> existing = refundRepository.findByPaymentIdAndIdempotencyKey(paymentId, idempotencyKey);
            if (existing.isPresent()) {
                log.info("Refund reservation for paymentId={} matched an existing row (id={}, status={}) by idempotency key '{}'.",
                        paymentId, existing.get().getId(), existing.get().getStatus(), idempotencyKey);
                return ReservationResult.alreadyReserved(existing.get());
            }
        }

        long remainingRefundablePaise = payment.getAmountPaid() - payment.getRefundedAmountPaise();
        if (request.getAmount() == null || request.getAmount() <= 0) {
            return ReservationResult.rejected("Refund amount must be positive.");
        }
        if (remainingRefundablePaise <= 0) {
            log.warn("Refund reservation rejected: payment {} is already fully refunded (reserved/consumed so far={} paise, paid={} paise).",
                    paymentId, payment.getRefundedAmountPaise(), payment.getAmountPaid());
            return ReservationResult.rejected("This payment has already been fully refunded.");
        }
        if (request.getAmount() > remainingRefundablePaise) {
            log.warn("Refund reservation rejected: requested {} paise exceeds remaining refundable {} paise for paymentId={}",
                    request.getAmount(), remainingRefundablePaise, paymentId);
            return ReservationResult.rejected(
                    "Refund amount exceeds the remaining refundable balance (" + remainingRefundablePaise + " paise).");
        }

        boolean isManualPayment = payment.getManualPaymentMode() != null;
        if (!isManualPayment) {
            String razorpayPaymentId = payment.getPaymentId();
            if (razorpayPaymentId == null || razorpayPaymentId.isBlank()) {
                return ReservationResult.rejected("Cannot refund: no Razorpay payment ID associated with this record.");
            }
        }

        Refund refund = new Refund();
        refund.setPaymentId(paymentId);
        refund.setSchoolId(schoolId);
        refund.setStudentId(payment.getStudentId());
        refund.setSession(payment.getSession());
        refund.setAmountPaise(request.getAmount());
        refund.setReason(request.getReason());
        refund.setStatus(STATUS_PENDING);
        refund.setIdempotencyKey(idempotencyKey);
        // monthsRefunded is NOT NULL at the DB level (V6) — a real value is only known once the
        // allocation-reversal plan runs at finalize time; the all-zero mask is a neutral
        // placeholder, never read as a real reversal outcome while status is still PENDING.
        refund.setMonthsRefunded("000000000000");
        refund.setLegacyApproximation(false);
        // Server-generated, never client-supplied — distinct from idempotencyKey above. Stable
        // for the lifetime of this logical refund: a retry that hits the ALREADY_RESERVED branch
        // above returns this same row/key rather than minting a new one.
        refund.setProviderIdempotencyKey(UUID.randomUUID().toString());

        Refund savedRefund = refundRepository.save(refund);

        payment.setRefundedAmountPaise(payment.getRefundedAmountPaise() + request.getAmount());
        Payment savedPayment = paymentRepository.save(payment);

        log.info("Refund capacity reserved: paymentId={} refundId={} amount={} paise, refundedAmountPaise now {} of {}.",
                paymentId, savedRefund.getId(), request.getAmount(), savedPayment.getRefundedAmountPaise(), savedPayment.getAmountPaid());

        return ReservationResult.reserved(savedRefund, savedPayment);
    }

    /**
     * Transaction B ("Finalize", success path): called strictly after a provider refund has
     * been confirmed (or immediately, for a manual payment with no gateway leg at all) — never
     * while any lock from {@link #reserve} is held, since that transaction has already
     * committed. Re-locks the Payment fresh and performs the EXACT reversal logic this
     * application has always used (allocation-ledger-based when the payment has one, the
     * pre-ledger oldest-month-first approximation otherwise) — moved here unchanged from the
     * pre-Phase-B {@code PaymentService.processRefund}, not redesigned.
     * <p>
     * Idempotent against a duplicate call for the same refundId: if the row is no longer
     * PENDING (already finalized by an earlier call), this returns the already-persisted result
     * instead of re-applying the reversal a second time.
     */
    @Transactional
    public Map<String, Object> finalizeSuccessfulRefund(Long paymentId, Long refundId, String providerRefundId,
                                                          String actorUsername, String actorRole, String ipAddress) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment " + paymentId + " vanished between reservation and finalization."));
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new IllegalStateException("Refund " + refundId + " vanished between reservation and finalization."));

        if (!STATUS_PENDING.equals(refund.getStatus())) {
            log.info("Finalize called for refundId={} but it is already '{}' — returning existing result, not re-applying.",
                    refundId, refund.getStatus());
            return responseFor(refund);
        }

        Long schoolId = payment.getSchoolId();
        List<PaymentStudentFeesAllocation> allocations = paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(paymentId);
        boolean legacyApproximation = allocations.isEmpty();
        String monthsReversedMask;
        List<Integer> monthsActuallyTouched;

        if (!legacyApproximation) {
            record AllocationReversalPlan(PaymentStudentFeesAllocation allocation, long portionPaise) {}
            List<AllocationReversalPlan> plan = new ArrayList<>();
            long remainingToRefund = refund.getAmountPaise();
            for (PaymentStudentFeesAllocation allocation : allocations) {
                if (remainingToRefund <= 0) break;
                long alreadyReversed = allocationRefundRepository.sumAmountPaiseByAllocationId(allocation.getId());
                long remainingInAllocation = allocation.getAmountPaise() - alreadyReversed;
                if (remainingInAllocation <= 0) continue;
                long portion = Math.min(remainingToRefund, remainingInAllocation);
                plan.add(new AllocationReversalPlan(allocation, portion));
                remainingToRefund -= portion;
            }
            if (plan.isEmpty()) {
                log.error("Refund inconsistency for paymentId={} refundId={}: payment-level ledger allowed {} paise but no " +
                        "allocation has any remaining balance to reverse. The provider refund (id={}) already succeeded — " +
                        "this refund remains PENDING with a confirmed provider refund; it must be reconciled manually, NOT retried as a new refund.",
                        paymentId, refundId, refund.getAmountPaise(), providerRefundId);
                throw new IllegalStateException("Cannot reconcile refund amount against this payment's allocation ledger.");
            }

            StringBuilder mask = new StringBuilder("000000000000");
            monthsActuallyTouched = new ArrayList<>();
            for (AllocationReversalPlan p : plan) {
                mask.setCharAt(p.allocation().getMonth() - 1, '1');
                monthsActuallyTouched.add(p.allocation().getMonth());
            }
            monthsReversedMask = mask.toString();

            for (AllocationReversalPlan p : plan) {
                AllocationRefund allocationRefund = new AllocationRefund();
                allocationRefund.setAllocationId(p.allocation().getId());
                allocationRefund.setRefundId(refund.getId());
                allocationRefund.setStudentFeesId(p.allocation().getStudentFeesId());
                allocationRefund.setAmountPaise(p.portionPaise());
                allocationRefundRepository.save(allocationRefund);
            }

            for (Long studentFeesId : plan.stream().map(p -> p.allocation().getStudentFeesId()).distinct().toList()) {
                StudentFees fee = studentFeesRepository.findByIdForUpdate(studentFeesId);
                if (fee != null) {
                    recomputeStudentFeesNetState(fee, schoolId);
                }
            }
        } else {
            log.warn("Payment {} predates the allocation ledger — falling back to approximate oldest-month-first reversal against Payment.month.", paymentId);
            LegacyReversalResult legacyResult = reverseLegacyByMonthBitmask(payment, schoolId, refund.getAmountPaise());
            monthsReversedMask = legacyResult.monthsReversedMask();
            monthsActuallyTouched = legacyResult.monthsTouched();
        }

        refund.setProviderRefundId(providerRefundId);
        refund.setStatus(STATUS_SUCCESS);
        refund.setMonthsRefunded(monthsReversedMask);
        refund.setLegacyApproximation(legacyApproximation);
        Refund savedRefund = refundRepository.save(refund);

        payment.setStatus(payment.getRefundedAmountPaise() >= payment.getAmountPaid() ? "refunded" : "partially_refunded");
        paymentRepository.save(payment);

        businessNotifications.studentAndParents(schoolId, payment.getStudentId(),
                NotificationAudienceType.STUDENT_WITH_FEE_PARENTS,
                NotificationEventCode.PAYMENT_REFUNDED, NotificationCategory.FEES_PAYMENTS,
                "Payment Refund Processed", "A refund for your fee payment has been processed.",
                "Refund", String.valueOf(savedRefund.getId()), "/dashboard/payment-history", actorUsername,
                "payment-refund:" + savedRefund.getId(), java.util.Set.of(ExternalDeliveryChannel.PUSH));

        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("paymentId", paymentId);
            details.put("refundId", savedRefund.getId());
            details.put("providerRefundId", providerRefundId);
            details.put("studentId", payment.getStudentId());
            details.put("session", payment.getSession());
            details.put("monthsRefunded", monthsReversedMask);
            details.put("amountPaise", savedRefund.getAmountPaise());
            details.put("legacyApproximation", legacyApproximation);
            details.put("actor", actorUsername);
            details.put("timestamp", java.time.LocalDateTime.now().toString());
            auditService.log(actorUsername, actorRole, "REFUND_PAYMENT", "Payment", paymentId.toString(),
                    null, objectMapper.writeValueAsString(details), ipAddress != null ? ipAddress : "SYSTEM");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        log.info("Refund finalized for paymentId={} refundId={} amount={} paise, months touched={}, legacyApproximation={}.",
                paymentId, savedRefund.getId(), savedRefund.getAmountPaise(), monthsActuallyTouched, legacyApproximation);

        return responseFor(savedRefund);
    }

    /**
     * Releases reserved capacity when a refund is DEFINITIVELY known to have not happened at
     * the provider — never for an ambiguous outcome (a timeout, a dropped connection, or any
     * other case where the provider may have already processed the refund). Idempotent: a
     * refund already FAILED is a no-op (capacity was released exactly once, by whichever call
     * got there first), and a refund already SUCCESS is never released regardless of how this
     * is called — consumed capacity for a real, confirmed refund must never be freed back up.
     * <p>
     * Not currently invoked anywhere in Phase B: this codebase's Razorpay SDK integration
     * (RazorpayService.createRefund) cannot reliably distinguish a definitive provider
     * rejection from an ambiguous network failure — both surface as the same
     * {@code RazorpayException} type with no structured field to tell them apart (verified by
     * reading the SDK's own source; see the Phase B final report). This method exists as the
     * tested, ready primitive for whatever DOES have that certainty — a future webhook-driven
     * {@code refund.failed} reconciliation, or a deliberate manual/ops action — not for Phase
     * B's own createRefund failure path, which must default to leaving the refund PENDING.
     */
    @Transactional
    public void markFailedAndRelease(Long paymentId, Long refundId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null) {
            log.warn("markFailedAndRelease: payment {} not found — nothing to release.", paymentId);
            return;
        }
        Refund refund = refundRepository.findById(refundId).orElse(null);
        if (refund == null) {
            log.warn("markFailedAndRelease: refund {} not found — nothing to release.", refundId);
            return;
        }
        if (!STATUS_PENDING.equals(refund.getStatus())) {
            log.info("markFailedAndRelease: refund {} is already '{}' — no-op (release happens at most once; SUCCESS is never released).",
                    refundId, refund.getStatus());
            return;
        }

        refund.setStatus(STATUS_FAILED);
        refundRepository.save(refund);

        long released = Math.max(0, payment.getRefundedAmountPaise() - refund.getAmountPaise());
        payment.setRefundedAmountPaise(released);
        paymentRepository.save(payment);

        log.info("Refund capacity released: paymentId={} refundId={} amount={} paise, refundedAmountPaise now {}.",
                paymentId, refundId, refund.getAmountPaise(), released);
    }

    // ═══════════════════════════ Refund-Integrity Hardening, Phase C ═══════════════════════════

    /**
     * Persists a known provider refund id on a still-PENDING Refund row as early as possible —
     * before attempting the (possibly-failing) local finalization — so a confirmed provider
     * operation is never lost even if {@link #finalizeSuccessfulRefund} itself throws (e.g. the
     * allocation-ledger-inconsistency guard). A no-op if the row is no longer PENDING (already
     * resolved by something else) or already has a providerRefundId (never overwritten — it is
     * the stable identity of the one provider operation this logical refund owns).
     */
    @Transactional
    public void recordProviderRefundId(Long paymentId, Long refundId, String providerRefundId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null) {
            return;
        }
        Refund refund = refundRepository.findById(refundId).orElse(null);
        if (refund == null || !STATUS_PENDING.equals(refund.getStatus()) || refund.getProviderRefundId() != null) {
            return;
        }
        refund.setProviderRefundId(providerRefundId);
        refundRepository.save(refund);
        log.info("Provider refund id persisted early: paymentId={} refundId={} providerRefundId={}.",
                paymentId, refundId, providerRefundId);
    }

    public enum ReconciliationOutcome { FINALIZED, RELEASED, NO_OP, REJECTED_MISMATCH }

    public record ReconciliationResult(ReconciliationOutcome outcome, String message) {}

    /**
     * The single reconciliation state machine — called identically from a refund.* webhook and
     * from {@link RazorpayService#reconcileRefund} (pull-based), so the two can never disagree
     * about what a given provider state means. Opens exactly one transaction: locks Payment,
     * re-reads Refund's CURRENT status inside that lock (never trusting a status read before
     * this call, e.g. from a provider lookup that ran outside any transaction), and applies at
     * most one transition.
     * <p>
     * Case table (using Razorpay's real status vocabulary — see
     * {@link RazorpayService#PROVIDER_STATUS_PENDING} and siblings):
     * <ul>
     *   <li>local SUCCESS or FAILED — no-op, regardless of what the provider/webhook now says
     *       (Cases D/E). A refund is never re-opened once terminal.</li>
     *   <li>local PENDING, provider {@code processed} — finalize now, through the exact same
     *       {@link #finalizeSuccessfulRefund} the client-verify path uses (Case A) — this is
     *       what recovers "provider succeeded, local finalization failed": the row is still
     *       PENDING, so this call retries finalize, not create.</li>
     *   <li>local PENDING, provider {@code pending} — no-op beyond persisting the provider id if
     *       not already known (Case B).</li>
     *   <li>local PENDING, provider {@code failed} or {@code reversed} — {@link #markFailedAndRelease}
     *       (Case C). Researched against official Razorpay documentation for Phase D, not left
     *       conservative by default as Phase C did: a reversed refund's bank-side credit missed
     *       its ~48-hour window, and Razorpay's own docs say the amount then returns to the
     *       merchant — the customer never received it, financially equivalent to a failure.</li>
     *   <li>local PENDING, any other unrecognized status — conservative no-op; never guessed
     *       into either terminal state.</li>
     * </ul>
     * Before applying either terminal transition, cross-checks whatever provider identity data
     * is available (payment id, amount, currency) against the local Refund/Payment — a validly
     * signed webhook can still reference a different valid payment/refund in the same Razorpay
     * account, so signature validity alone is never treated as identity proof.
     */
    @Transactional
    public ReconciliationResult resolveFromProviderState(String providerRefundId, String providerStatus,
                                                          String providerPaymentId, Long providerAmountPaise, String providerCurrency,
                                                          String actorUsername, String actorRole, String ipAddress) {
        Refund refund = refundRepository.findByProviderRefundId(providerRefundId).orElse(null);
        if (refund == null) {
            log.warn("Reconciliation: no local Refund found for providerRefundId={} — ignoring.", providerRefundId);
            return new ReconciliationResult(ReconciliationOutcome.NO_OP, "unknown provider refund id");
        }

        if (STATUS_SUCCESS.equals(refund.getStatus()) || STATUS_FAILED.equals(refund.getStatus())) {
            log.info("Reconciliation: refund {} is already '{}' — no-op.", refund.getId(), refund.getStatus());
            return new ReconciliationResult(ReconciliationOutcome.NO_OP, "already " + refund.getStatus());
        }

        Payment payment = paymentRepository.findByIdForUpdate(refund.getPaymentId())
                .orElseThrow(() -> new IllegalStateException("Payment " + refund.getPaymentId() + " vanished during reconciliation."));

        // Re-read the refund's status now that the Payment lock is held — everything above ran
        // without a lock, so nothing observed there is trusted for the decision itself. This
        // MUST be a genuine round-trip to the database, not `refundRepository.findById(...)`:
        // `refund` is already a managed entity in this transaction's persistence context (from
        // the unlocked findByProviderRefundId call above), and JPA's first-level cache resolves
        // findById-by-id from that identity map WITHOUT re-querying — silently returning the
        // exact same, pre-lock, potentially-stale object. entityManager.refresh() is the
        // supported way to force Hibernate to re-SELECT and overwrite this object's fields from
        // the database. Proven necessary by a genuine two-worker race in
        // RefundReconciliationJobPostgresIT: without this, a second concurrent caller finalized
        // a refund the first caller had *already* just finalized, because its "re-check" saw
        // its own stale in-memory copy instead of the first caller's committed SUCCESS.
        entityManager.refresh(refund);
        Refund lockedRefund = refund;
        if (STATUS_SUCCESS.equals(lockedRefund.getStatus()) || STATUS_FAILED.equals(lockedRefund.getStatus())) {
            log.info("Reconciliation: refund {} resolved to '{}' by a concurrent call — no-op.", lockedRefund.getId(), lockedRefund.getStatus());
            return new ReconciliationResult(ReconciliationOutcome.NO_OP, "already " + lockedRefund.getStatus());
        }

        boolean terminalTransitionRequested = RazorpayService.PROVIDER_STATUS_PROCESSED.equals(providerStatus)
                || RazorpayService.PROVIDER_STATUS_FAILED.equals(providerStatus)
                || RazorpayService.PROVIDER_STATUS_REVERSED.equals(providerStatus);
        if (terminalTransitionRequested) {
            if (providerPaymentId != null && payment.getPaymentId() != null && !providerPaymentId.equals(payment.getPaymentId())) {
                log.error("Reconciliation REJECTED: providerRefundId={} reports payment_id={} but local refund {} " +
                        "belongs to payment_id={} — refusing to apply any state change.",
                        providerRefundId, providerPaymentId, lockedRefund.getId(), payment.getPaymentId());
                return new ReconciliationResult(ReconciliationOutcome.REJECTED_MISMATCH, "payment identity mismatch");
            }
            if (providerAmountPaise != null && providerAmountPaise != lockedRefund.getAmountPaise()) {
                log.error("Reconciliation REJECTED: providerRefundId={} reports amount={} paise but local refund {} " +
                        "expects {} paise — refusing to apply any state change.",
                        providerRefundId, providerAmountPaise, lockedRefund.getId(), lockedRefund.getAmountPaise());
                return new ReconciliationResult(ReconciliationOutcome.REJECTED_MISMATCH, "amount mismatch");
            }
            if (providerCurrency != null && !"INR".equalsIgnoreCase(providerCurrency)) {
                log.error("Reconciliation REJECTED: providerRefundId={} reports currency={} — refusing to apply " +
                        "any state change.", providerRefundId, providerCurrency);
                return new ReconciliationResult(ReconciliationOutcome.REJECTED_MISMATCH, "currency mismatch");
            }
        }

        if (RazorpayService.PROVIDER_STATUS_PROCESSED.equals(providerStatus)) {
            finalizeSuccessfulRefund(payment.getId(), lockedRefund.getId(), providerRefundId, actorUsername, actorRole, ipAddress);
            return new ReconciliationResult(ReconciliationOutcome.FINALIZED, "finalized");
        }
        if (RazorpayService.PROVIDER_STATUS_FAILED.equals(providerStatus)
                || RazorpayService.PROVIDER_STATUS_REVERSED.equals(providerStatus)) {
            // Phase D: "reversed" researched against official Razorpay documentation (Refund
            // Failures / refunds FAQ) rather than left conservative-by-default as Phase C did.
            // Razorpay's own docs: a bank-side refund credit has a ~48-hour window; "if the
            // credit doesn't show in the destination account, the amount gets credited to the
            // source" — i.e. back to the merchant. A reversed refund means the customer never
            // actually received the money and the funds returned to the school, which is
            // financially equivalent to a failure for this refund's own reservation — so it
            // releases capacity through the exact same idempotent primitive "failed" uses. This
            // never contradicts an already-terminal SUCCESS (the no-op guard above already
            // returned for that case) — the residual case Razorpay's docs don't fully resolve
            // (a "reversed" report arriving for a refund THIS system already marked SUCCESS,
            // if that's even possible) is reported as a known limitation, not guessed at here.
            markFailedAndRelease(payment.getId(), lockedRefund.getId());
            return new ReconciliationResult(ReconciliationOutcome.RELEASED, "released (" + providerStatus + ")");
        }
        if (RazorpayService.PROVIDER_STATUS_PENDING.equals(providerStatus)) {
            if (lockedRefund.getProviderRefundId() == null) {
                lockedRefund.setProviderRefundId(providerRefundId);
                refundRepository.save(lockedRefund);
            }
            return new ReconciliationResult(ReconciliationOutcome.NO_OP, "still pending");
        }

        // Any status this codebase doesn't recognize at all — conservative by construction:
        // never guessed into a terminal state.
        log.warn("Reconciliation: providerRefundId={} reported status '{}' — not a recognized terminal/pending " +
                "value; leaving refund {} PENDING.", providerRefundId, providerStatus, lockedRefund.getId());
        return new ReconciliationResult(ReconciliationOutcome.NO_OP, "unrecognized provider status: " + providerStatus);
    }

    private Map<String, Object> responseFor(Refund refund) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("refundId", refund.getId());
        response.put("providerRefundId", refund.getProviderRefundId());
        response.put("amount", refund.getAmountPaise());
        response.put("status", refund.getStatus());
        response.put("monthsRefunded", refund.getMonthsRefunded());
        response.put("legacyApproximation", refund.isLegacyApproximation());
        return response;
    }

    /** Moved unchanged from the pre-Phase-B PaymentService — see its original javadoc there for
     * the full rationale. Recomputes a StudentFees row's paid/amountPaid from its TOTAL net
     * allocation across every payment that ever touched it, never a delta subtraction. */
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
            fullyPaid = netPaise > 0;
        }
        fee.setPaid(fullyPaid);
        studentFeesRepository.save(fee);
    }

    private record LegacyReversalResult(String monthsReversedMask, List<Integer> monthsTouched) {}

    /** Moved unchanged from the pre-Phase-B PaymentService — the pre-ledger approximation kept
     * ONLY for refunding a payment with no allocation rows at all. */
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

    /** Moved unchanged from the pre-Phase-B PaymentService. */
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
}
