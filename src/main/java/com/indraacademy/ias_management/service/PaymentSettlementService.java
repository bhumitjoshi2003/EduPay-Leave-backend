package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.PaymentOrder;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.PaymentOrderRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Canonical, atomic settlement of a Razorpay payment — the single place financial DB state
 * changes for BOTH the client-verify path and the webhook-recovery path (Razorpay
 * Payment-Integrity Hardening, Phase B introduced this for client-verify; Phase C converged
 * webhook recovery onto the same method rather than duplicating settlement logic). Everything
 * in {@link #settle} runs inside one transaction: the Payment row, the PaymentOrder
 * consumption, the attendance charge-paid flip, and the StudentFees allocation all land
 * together, or none of them do — regardless of which path triggered it.
 * <p>
 * Deliberately owns none of: signature/webhook-HMAC verification (stays in
 * {@link RazorpayService#verifyPayment} and {@code WebhookController} respectively, outside
 * this transaction), provider amount/currency cross-checking for the webhook path (the
 * caller's job before ever calling this — see {@link RazorpayService}'s webhook handling), or
 * notifications/email (a side effect that must never determine whether settlement itself
 * succeeded — sent by the caller only after this method returns, i.e. only after commit).
 * <p>
 * <b>Lock order</b>: {@link PaymentOrderRepository#findByOrderIdForUpdate} is acquired first,
 * before anything else in this method touches the database. {@link StudentFeesService#markFeesAsPaid}
 * acquires its own per-row {@code StudentFees} lock strictly after that, inside the same
 * transaction — so the order is always PaymentOrder → StudentFees, never the reverse.
 * {@link AttendanceService#updateChargePaidAfterPayment} does not lock StudentFees or
 * PaymentOrder at all (a plain bulk UPDATE on {@code attendance}), so it cannot introduce a
 * reverse-order path either.
 */
@Service
public class PaymentSettlementService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSettlementService.class);

    @Autowired private PaymentOrderRepository paymentOrderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private AttendanceService attendanceService;
    @Autowired private StudentFeesService studentFeesService;

    public enum Outcome { SETTLED, ALREADY_SETTLED, REJECTED }

    /** Which caller is asking for this settlement — determines only what gets stored as the
     * Payment's razorpaySignature (a real client HMAC for CLIENT_VERIFY, since that's the only
     * proof the client path ever has; a fixed sentinel for RAZORPAY_WEBHOOK, mirroring the
     * pre-existing "MANUAL-PAYMENT" sentinel StudentFeesService.recordManualPayment already
     * uses for the same reason — no client-shaped signature exists for that path either).
     * Authentication/authorization for both paths happens entirely before this method is ever
     * called (client HMAC in RazorpayService#verifyPayment, webhook HMAC in WebhookController)
     * — this enum carries no trust decision of its own. */
    public enum SettlementSource { CLIENT_VERIFY, RAZORPAY_WEBHOOK }

    private static final String WEBHOOK_SIGNATURE_SENTINEL = "WEBHOOK_VERIFIED";

    /** {@code payment} is populated only for {@link Outcome#SETTLED} — the freshly-saved row,
     * for the caller's post-commit notification/email step. Null for every other outcome
     * (nothing new to notify about). */
    public record SettlementResult(Outcome outcome, String message, Payment payment) {
        static SettlementResult settled(Payment payment) {
            return new SettlementResult(Outcome.SETTLED, "Payment Verified Successfully", payment);
        }

        static SettlementResult alreadySettled() {
            return new SettlementResult(Outcome.ALREADY_SETTLED, "Payment already verified.", null);
        }

        static SettlementResult rejected(String message) {
            return new SettlementResult(Outcome.REJECTED, message, null);
        }
    }

    /**
     * Settles one Razorpay payment, whether the caller is the client-verify path or the
     * webhook-recovery path — both converge here (Razorpay Payment-Integrity Hardening, Phase
     * C). The caller has already authenticated the request before calling this (client HMAC in
     * {@link RazorpayService#verifyPayment}, webhook HMAC in {@code WebhookController}, plus —
     * for the webhook path only — provider amount/currency cross-checked against PaymentOrder
     * by the caller before this is invoked) — a valid signature only proves a payment was
     * captured for {@code orderId}, never which student/amount/months it was for, which is why
     * every business field below comes from the server-persisted PaymentOrder, never a
     * caller-supplied value. {@code schoolId} for the webhook path is the PaymentOrder's own
     * schoolId (resolved by the caller before this call) rather than an independently
     * authenticated tenant — the check at B below is then a no-op tautology for that path,
     * which is correct: there is no separate "caller's claimed school" to cross-check against
     * a server-to-server webhook.
     */
    @Transactional
    public SettlementResult settle(String orderId, String paymentId, String signature, Long schoolId,
                                    SettlementSource source) {
        // Cheap pre-lock idempotency check — handles the overwhelmingly common repeat-call
        // case (client retry after already receiving success) without ever taking a lock.
        if (paymentRepository.existsByPaymentId(paymentId)) {
            log.warn("Duplicate verify call for Payment ID: {} — already persisted, returning success.", paymentId);
            return SettlementResult.alreadySettled();
        }

        // A. Lock the PaymentOrder for the rest of this transaction — serializes any
        // concurrent settlement attempt (a duplicate client retry, a webhook-triggered
        // settlement, or a client-verify/webhook race) against the same order.
        PaymentOrder paymentOrder = paymentOrderRepository.findByOrderIdForUpdate(orderId).orElse(null);
        if (paymentOrder == null) {
            log.error("No server-side order record found for Order ID: {} — rejecting.", orderId);
            return SettlementResult.rejected("Payment Verification Failed: Unknown order.");
        }

        // B. Validate under the lock. PaymentOrder's own persisted fields (amount, session,
        // month, class, student) remain the sole source of truth, exactly as before — there is
        // no separate amount signal to cross-check at verify time beyond what createOrder
        // already validated when this row was written.
        if (!paymentOrder.getSchoolId().equals(schoolId)) {
            log.error("Order {} belongs to schoolId={} but caller is schoolId={} — rejecting.",
                    orderId, paymentOrder.getSchoolId(), schoolId);
            return SettlementResult.rejected("Payment Verification Failed: Order does not belong to this school.");
        }

        // C. Re-check idempotency now that we hold the lock — closes the window between the
        // pre-lock check above and actually acquiring it. Takes priority over the consumed
        // check below: an order that's consumed BY THIS EXACT paymentId (this retry) must
        // still report ALREADY_SETTLED, never REJECTED.
        if (paymentRepository.existsByPaymentId(paymentId)) {
            log.warn("Duplicate verify call for Payment ID: {} (detected under lock) — already persisted.", paymentId);
            return SettlementResult.alreadySettled();
        }

        if (paymentOrder.isConsumed()) {
            // Consumed, but not by this paymentId (ruled out immediately above, under this
            // same lock) — a different payment attempting to reuse an already-settled order.
            // Reject explicitly; never silently treat this as success.
            log.error("Order {} was already consumed — rejecting reuse with Payment ID: {}.", orderId, paymentId);
            return SettlementResult.rejected("Payment Verification Failed: Order already used.");
        }

        // Fresh order + fresh paymentId — proceed.
        String studentId = paymentOrder.getStudentId();
        String studentName = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .map(Student::getName).orElse(studentId);

        String storedSignature = source == SettlementSource.RAZORPAY_WEBHOOK ? WEBHOOK_SIGNATURE_SENTINEL : signature;
        Payment payment = buildPayment(paymentOrder, paymentId, orderId, storedSignature, schoolId, studentName);

        // E. Create Payment.
        Payment savedPayment;
        try {
            savedPayment = paymentRepository.save(payment);
        } catch (DataIntegrityViolationException e) {
            return handleSaveConflict(orderId, paymentId, e);
        }
        log.info("Payment saved successfully to DB. Record ID: {}", savedPayment.getId());

        // F. Mark PaymentOrder consumed.
        paymentOrder.setConsumed(true);
        paymentOrderRepository.save(paymentOrder);

        // G. Join the same transaction (both @Transactional(REQUIRED) by default, and both
        // are different Spring beans so the proxy correctly enlists them) — a failure in
        // either rolls back the Payment insert and the PaymentOrder consumption above too.
        attendanceService.updateChargePaidAfterPayment(studentId, paymentOrder.getSession());
        studentFeesService.markFeesAsPaid(savedPayment);
        log.debug("Attendance and StudentFees marked as paid.");

        return SettlementResult.settled(savedPayment);
    }

    /**
     * A save() that violates a unique constraint could mean one of two very different things
     * — distinguish them by re-reading the canonical row, never by assuming success:
     * <ul>
     *   <li>{@code uq_payment_payment_id} fired: a concurrent request (duplicate client retry,
     *   or a racing webhook settling the same paymentId) already settled this exact paymentId
     *   between our idempotency check and this save. existsByPaymentId now reads true. Genuinely
     *   already settled — report it as such.</li>
     *   <li>{@code uq_payment_order_id_razorpay} (V62) fired instead: a DIFFERENT paymentId
     *   collided on this order_id. existsByPaymentId still reads false. This is a conflict,
     *   not a success — the PaymentOrder lock above should have prevented this in practice,
     *   so seeing it at all means something raced outside that lock; reject rather than
     *   guess.</li>
     * </ul>
     */
    private SettlementResult handleSaveConflict(String orderId, String paymentId, DataIntegrityViolationException e) {
        if (paymentRepository.existsByPaymentId(paymentId)) {
            log.warn("Duplicate payment save race detected for paymentId={} — already recorded by a concurrent request.", paymentId, e);
            return SettlementResult.alreadySettled();
        }
        log.error("Payment save for orderId={} paymentId={} violated a data-integrity constraint, but no payment " +
                        "row exists for this paymentId — a different payment collided on this order. Rejecting.",
                orderId, paymentId, e);
        return SettlementResult.rejected("Payment Verification Failed: Order already used.");
    }

    private Payment buildPayment(PaymentOrder paymentOrder, String paymentId, String orderId, String signature,
                                  Long schoolId, String studentName) {
        Payment payment = new Payment();
        payment.setStudentId(paymentOrder.getStudentId());
        payment.setStudentName(studentName);
        payment.setClassName(paymentOrder.getClassName());
        payment.setSession(paymentOrder.getSession());
        payment.setMonth(paymentOrder.getMonth());
        int amountInPaise = paymentOrder.getAmount();
        payment.setAmount(amountInPaise); // Stored in paise
        payment.setPaymentId(paymentId);
        payment.setOrderId(orderId);
        payment.setBusFee(paymentOrder.getBusFee());
        payment.setTuitionFee(paymentOrder.getTuitionFee());
        payment.setAnnualCharges(paymentOrder.getAnnualCharges());
        payment.setLabCharges(paymentOrder.getLabCharges());
        payment.setEcaProject(paymentOrder.getEcaProject());
        payment.setExaminationFee(paymentOrder.getExaminationFee());
        payment.setPaidManually(false);
        payment.setAmountPaid(amountInPaise); // Stored in paise
        payment.setRazorpaySignature(signature);
        payment.setAdditionalCharges(paymentOrder.getAdditionalCharges());
        payment.setLateFees(paymentOrder.getLateFees());
        payment.setPlatformFee(paymentOrder.getPlatformFee());
        payment.setSchoolId(schoolId);
        return payment;
    }
}
