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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Refund-Integrity Hardening, Phase B — unit tests for the atomic reservation/finalization
 * boundary. Extracted from PaymentServiceTest (which owned all refund tests pre-Phase-B) because
 * the logic itself moved to this class — see RefundSettlementService's own javadoc for why a
 * separate {@code @Service} bean was required for the transaction boundaries to be real.
 * <p>
 * True concurrent-thread/real-DB-lock proof lives in RefundSettlementServicePostgresIT, not
 * here — this suite proves the sequential logic each method applies, using the same "serialize
 * via the pessimistic-lock finder, then verify sequential composition" pattern the pre-Phase-B
 * suite used for the equivalent claim.
 */
@ExtendWith(MockitoExtension.class)
class RefundSettlementServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private PaymentStudentFeesAllocationRepository paymentAllocationRepository;
    @Mock private AllocationRefundRepository allocationRefundRepository;
    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private FeeCalculationService feeCalculationService;
    @Mock private BusinessNotificationService businessNotifications;
    @Mock private AuditService auditService;
    @Mock private jakarta.persistence.EntityManager entityManager;

    private RefundSettlementService service;

    private static final Long SCHOOL_ID = 1L;
    private static final Long PAYMENT_ID = 100L;

    @BeforeEach
    void setUp() {
        service = new RefundSettlementService();
        ReflectionTestUtils.setField(service, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(service, "refundRepository", refundRepository);
        ReflectionTestUtils.setField(service, "paymentAllocationRepository", paymentAllocationRepository);
        ReflectionTestUtils.setField(service, "allocationRefundRepository", allocationRefundRepository);
        ReflectionTestUtils.setField(service, "studentFeesRepository", studentFeesRepository);
        ReflectionTestUtils.setField(service, "feeCalculationService", feeCalculationService);
        ReflectionTestUtils.setField(service, "businessNotifications", businessNotifications);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        // A plain Mockito mock: refresh() no-ops, leaving whatever the test already configured
        // on the Refund object as-is — exactly the behavior these tests want (there is no real
        // persistence context to refresh from). Real-Postgres proof that refresh() genuinely
        // re-reads from the database lives in RefundReconciliationJobPostgresIT.
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());

        lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(any())).thenReturn(List.of());
        lenient().when(allocationRefundRepository.sumAmountPaiseByAllocationId(any())).thenReturn(0L);
        lenient().when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(any())).thenReturn(0L);
        lenient().when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(any())).thenReturn(0L);
        lenient().when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(nextRefundId++);
            }
            return r;
        });
    }

    private static long nextRefundId = 900L;

    private Payment razorpayPayment() {
        Payment p = new Payment();
        p.setId(PAYMENT_ID);
        p.setSchoolId(SCHOOL_ID);
        p.setStudentId("S1");
        p.setStudentName("Student One");
        p.setClassName("6A");
        p.setSession("2025-2026");
        p.setMonth("110000000000");
        p.setPaymentId("pay_razorpay123");
        p.setAmountPaid(400000);
        p.setStatus("success");
        p.setRefundedAmountPaise(0L);
        return p;
    }

    private Payment manualPayment() {
        Payment p = razorpayPayment();
        p.setManualPaymentMode("CASH");
        p.setPaymentId("MANUAL_abc123");
        return p;
    }

    private StudentFees paidRow(int month, BigDecimal amountPaid, boolean manuallyPaid) {
        StudentFees fee = new StudentFees();
        fee.setId(1000L + month);
        fee.setStudentId("S1");
        fee.setSchoolId(SCHOOL_ID);
        fee.setYear("2025-2026");
        fee.setMonth(month);
        fee.setPaid(true);
        fee.setManuallyPaid(manuallyPaid);
        fee.setAmountPaid(amountPaid);
        lenient().when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonthForUpdate("S1", SCHOOL_ID, "2025-2026", month))
                .thenReturn(fee);
        lenient().when(studentFeesRepository.findByIdForUpdate(fee.getId())).thenReturn(fee);
        lenient().when(feeCalculationService.resolveSchoolFeeDue(fee, SCHOOL_ID, "2025-2026"))
                .thenReturn(Optional.of(amountPaid));
        return fee;
    }

    private RefundRequest refundRequest(long amountPaise, String reason, String idempotencyKey) {
        RefundRequest req = new RefundRequest();
        req.setAmount(amountPaise);
        req.setReason(reason);
        req.setIdempotencyKey(idempotencyKey);
        return req;
    }

    // ═══════════════════════════ reserve() ═══════════════════════════

    @Test
    void reserve_fullAmount_createsPendingRefundAndReservesCapacity() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        var result = service.reserve(PAYMENT_ID, refundRequest(400000L, "full", null), SCHOOL_ID);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.RESERVED);
        assertThat(result.refund().getStatus()).isEqualTo(RefundSettlementService.STATUS_PENDING);
        assertThat(result.refund().getAmountPaise()).isEqualTo(400000L);
        assertThat(result.refund().getProviderIdempotencyKey()).isNotBlank();
        assertThat(payment.getRefundedAmountPaise()).isEqualTo(400000L);
        verify(paymentRepository).save(payment);
        verify(refundRepository).save(any(Refund.class));
    }

    @Test
    void reserve_partialAmount_reservesExactAmount() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        var result = service.reserve(PAYMENT_ID, refundRequest(150000L, "partial", null), SCHOOL_ID);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.RESERVED);
        assertThat(payment.getRefundedAmountPaise()).isEqualTo(150000L);
    }

    @Test
    void reserve_amountExceedsRemaining_rejected() {
        Payment payment = razorpayPayment();
        payment.setRefundedAmountPaise(300000L); // ₹1000 (100000 paise) remains
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        var result = service.reserve(PAYMENT_ID, refundRequest(150000L, "too much", null), SCHOOL_ID);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.REJECTED);
        assertThat(result.message()).contains("100000");
        assertThat(payment.getRefundedAmountPaise()).isEqualTo(300000L); // unchanged
        verify(refundRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void reserve_alreadyFullyRefunded_rejected() {
        Payment payment = razorpayPayment();
        payment.setRefundedAmountPaise(400000L); // == amountPaid
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        var result = service.reserve(PAYMENT_ID, refundRequest(1L, "duplicate", null), SCHOOL_ID);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.REJECTED);
        assertThat(result.message()).contains("already been fully refunded");
    }

    @Test
    void reserve_nonPositiveAmount_rejected() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        var result = service.reserve(PAYMENT_ID, refundRequest(0L, "zero", null), SCHOOL_ID);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.REJECTED);
        assertThat(result.message()).contains("positive");
    }

    @Test
    void reserve_paymentNotFound_rejected() {
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.empty());

        var result = service.reserve(PAYMENT_ID, refundRequest(1000L, "no such payment", null), SCHOOL_ID);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.REJECTED);
        assertThat(result.message()).isEqualTo("Payment not found.");
    }

    @Test
    void reserve_crossSchool_rejectedAsNotFound() {
        Payment payment = razorpayPayment();
        payment.setSchoolId(2L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        var result = service.reserve(PAYMENT_ID, refundRequest(1000L, "cross-school", null), SCHOOL_ID);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.REJECTED);
        assertThat(result.message()).isEqualTo("Payment not found.");
    }

    @Test
    void reserve_noRazorpayPaymentId_rejected() {
        Payment payment = razorpayPayment();
        payment.setPaymentId(""); // non-manual, but missing the gateway payment id
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        var result = service.reserve(PAYMENT_ID, refundRequest(1000L, "broken record", null), SCHOOL_ID);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.REJECTED);
        assertThat(result.message()).contains("no Razorpay payment ID");
        verify(refundRepository, never()).save(any());
    }

    @Test
    void reserve_manualPayment_allowsReservationWithoutRazorpayIdCheck() {
        Payment payment = manualPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        var result = service.reserve(PAYMENT_ID, refundRequest(200000L, "cash refund", null), SCHOOL_ID);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.RESERVED);
    }

    @Test
    void reserve_idempotencyKeyMatchesExistingPending_returnsAlreadyReserved_doesNotReReserve() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund existing = new Refund();
        existing.setId(555L);
        existing.setStatus(RefundSettlementService.STATUS_PENDING);
        existing.setProviderIdempotencyKey("stable-key-abc");
        when(refundRepository.findByPaymentIdAndIdempotencyKey(PAYMENT_ID, "retry-key")).thenReturn(Optional.of(existing));

        var result = service.reserve(PAYMENT_ID, refundRequest(100000L, "retry", "retry-key"), SCHOOL_ID);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.ALREADY_RESERVED);
        assertThat(result.refund().getId()).isEqualTo(555L);
        assertThat(result.refund().getProviderIdempotencyKey()).isEqualTo("stable-key-abc"); // never re-minted
        verify(refundRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void reserve_idempotencyKeyMatchesExistingSuccess_returnsAlreadyReserved() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund existing = new Refund();
        existing.setId(556L);
        existing.setStatus(RefundSettlementService.STATUS_SUCCESS);
        when(refundRepository.findByPaymentIdAndIdempotencyKey(PAYMENT_ID, "done-key")).thenReturn(Optional.of(existing));

        var result = service.reserve(PAYMENT_ID, refundRequest(100000L, "dup", "done-key"), SCHOOL_ID);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.ALREADY_RESERVED);
        assertThat(result.refund().getStatus()).isEqualTo(RefundSettlementService.STATUS_SUCCESS);
    }

    @Test
    void reserve_idempotencyKeyMatchesExistingFailed_returnsAlreadyReserved() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund existing = new Refund();
        existing.setId(557L);
        existing.setStatus(RefundSettlementService.STATUS_FAILED);
        when(refundRepository.findByPaymentIdAndIdempotencyKey(PAYMENT_ID, "dead-key")).thenReturn(Optional.of(existing));

        var result = service.reserve(PAYMENT_ID, refundRequest(100000L, "retry after failure", "dead-key"), SCHOOL_ID);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.ALREADY_RESERVED);
        assertThat(result.refund().getStatus()).isEqualTo(RefundSettlementService.STATUS_FAILED);
    }

    @Test
    void reserve_twoIndependentReservations_getDistinctProviderIdempotencyKeys() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        var first = service.reserve(PAYMENT_ID, refundRequest(100000L, "first", null), SCHOOL_ID);
        var second = service.reserve(PAYMENT_ID, refundRequest(100000L, "second", null), SCHOOL_ID);

        assertThat(first.refund().getProviderIdempotencyKey())
                .isNotBlank()
                .isNotEqualTo(second.refund().getProviderIdempotencyKey());
    }

    // ═══════════════════════════ finalizeSuccessfulRefund() ═══════════════════════════

    private Refund pendingRefund(Long id, Long paymentId, long amountPaise, String reason) {
        Refund refund = new Refund();
        refund.setId(id);
        refund.setPaymentId(paymentId);
        refund.setSchoolId(SCHOOL_ID);
        refund.setStudentId("S1");
        refund.setSession("2025-2026");
        refund.setAmountPaise(amountPaise);
        refund.setReason(reason);
        refund.setStatus(RefundSettlementService.STATUS_PENDING);
        refund.setMonthsRefunded("000000000000");
        refund.setLegacyApproximation(false);
        refund.setProviderIdempotencyKey("key-" + id);
        return refund;
    }

    private PaymentStudentFeesAllocation allocation(Long id, Long paymentId, Long studentFeesId, int month, long amountPaise) {
        PaymentStudentFeesAllocation a = new PaymentStudentFeesAllocation();
        a.setId(id);
        a.setPaymentId(paymentId);
        a.setStudentFeesId(studentFeesId);
        a.setSchoolId(SCHOOL_ID);
        a.setStudentId("S1");
        a.setSession("2025-2026");
        a.setMonth(month);
        a.setAmountPaise(amountPaise);
        return a;
    }

    @Test
    void finalizeSuccessfulRefund_ledgerBased_fullRefund_reversesAllocationAndMarksPaymentRefunded() {
        Payment payment = razorpayPayment();
        payment.setMonth("100000000000");
        payment.setAmountPaid(200000);
        payment.setRefundedAmountPaise(200000L); // already reserved by a prior reserve() call
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(700L, PAYMENT_ID, 200000L, "full");
        when(refundRepository.findById(700L)).thenReturn(Optional.of(refund));
        StudentFees month1 = paidRow(1, BigDecimal.valueOf(2000), false);
        PaymentStudentFeesAllocation alloc = allocation(10L, PAYMENT_ID, month1.getId(), 1, 200000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of(alloc));
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(month1.getId())).thenReturn(200000L);
        when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(month1.getId())).thenReturn(200000L);

        var response = service.finalizeSuccessfulRefund(PAYMENT_ID, 700L, "rfnd_1", "admin", "ADMIN", "127.0.0.1");

        assertThat(month1.getPaid()).isFalse();
        assertThat(month1.getAmountPaid()).isEqualByComparingTo("0");
        assertThat(payment.getStatus()).isEqualTo("refunded");
        assertThat(refund.getStatus()).isEqualTo(RefundSettlementService.STATUS_SUCCESS);
        assertThat(refund.getProviderRefundId()).isEqualTo("rfnd_1");
        assertThat(response.get("monthsRefunded")).isEqualTo("100000000000");
        verify(auditService).log(eq("admin"), eq("ADMIN"), eq("REFUND_PAYMENT"), eq("Payment"),
                eq(PAYMENT_ID.toString()), isNull(), anyString(), eq("127.0.0.1"));
    }

    @Test
    void finalizeSuccessfulRefund_partialRefund_marksPaymentPartiallyRefunded() {
        Payment payment = razorpayPayment(); // amountPaid = 400000
        payment.setRefundedAmountPaise(150000L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(701L, PAYMENT_ID, 150000L, "partial");
        when(refundRepository.findById(701L)).thenReturn(Optional.of(refund));
        StudentFees month1 = paidRow(1, BigDecimal.valueOf(2000), false);
        paidRow(2, BigDecimal.valueOf(2000), false);
        PaymentStudentFeesAllocation a1 = allocation(11L, PAYMENT_ID, month1.getId(), 1, 200000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of(a1));
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(month1.getId())).thenReturn(200000L);
        when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(month1.getId())).thenReturn(150000L);

        service.finalizeSuccessfulRefund(PAYMENT_ID, 701L, "rfnd_2", "admin", "ADMIN", "10.0.0.1");

        assertThat(payment.getStatus()).isEqualTo("partially_refunded");
        assertThat(month1.getAmountPaid()).isEqualByComparingTo("500");
    }

    @Test
    void finalizeSuccessfulRefund_legacyApproximation_noAllocationRows_usesMonthBitmask() {
        Payment payment = razorpayPayment();
        payment.setRefundedAmountPaise(150000L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(702L, PAYMENT_ID, 150000L, "legacy refund");
        when(refundRepository.findById(702L)).thenReturn(Optional.of(refund));
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of());
        StudentFees month1 = new StudentFees();
        month1.setStudentId("S1");
        month1.setSchoolId(SCHOOL_ID);
        month1.setYear("2025-2026");
        month1.setMonth(1);
        month1.setPaid(false);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonthForUpdate("S1", SCHOOL_ID, "2025-2026", 1))
                .thenReturn(month1);
        StudentFees month2 = paidRow(2, BigDecimal.valueOf(2000), false);

        var response = service.finalizeSuccessfulRefund(PAYMENT_ID, 702L, "rfnd_3", "admin", "ADMIN", "127.0.0.1");

        assertThat(response.get("legacyApproximation")).isEqualTo(true);
        assertThat(response.get("monthsRefunded")).isEqualTo("010000000000");
        assertThat(month2.getAmountPaid()).isEqualByComparingTo("500");
    }

    @Test
    void finalizeSuccessfulRefund_originalFeeSnapshotFieldsNeverTouched() {
        Payment payment = razorpayPayment();
        payment.setMonth("100000000000");
        payment.setAmountPaid(200000);
        payment.setRefundedAmountPaise(200000L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(703L, PAYMENT_ID, 200000L, "full");
        when(refundRepository.findById(703L)).thenReturn(Optional.of(refund));
        StudentFees month1 = paidRow(1, BigDecimal.valueOf(2000), false);
        month1.setBaseAmountDue(BigDecimal.valueOf(1800));
        month1.setBusFeeDue(BigDecimal.valueOf(200));
        month1.setDiscountAmount(BigDecimal.valueOf(50));
        month1.setAmountRuleSnapshot("{\"feeHeads\":[]}");
        PaymentStudentFeesAllocation alloc = allocation(12L, PAYMENT_ID, month1.getId(), 1, 200000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of(alloc));
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(month1.getId())).thenReturn(200000L);
        when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(month1.getId())).thenReturn(200000L);

        service.finalizeSuccessfulRefund(PAYMENT_ID, 703L, "rfnd_4", "admin", "ADMIN", "127.0.0.1");

        assertThat(month1.getBaseAmountDue()).isEqualByComparingTo("1800");
        assertThat(month1.getBusFeeDue()).isEqualByComparingTo("200");
        assertThat(month1.getDiscountAmount()).isEqualByComparingTo("50");
        assertThat(month1.getAmountRuleSnapshot()).isEqualTo("{\"feeHeads\":[]}");
    }

    @Test
    void finalizeSuccessfulRefund_providerRefundIdNull_manualPaymentStylePersistsCleanly() {
        Payment payment = manualPayment();
        payment.setAmountPaid(200000);
        payment.setRefundedAmountPaise(200000L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(704L, PAYMENT_ID, 200000L, "cash refund");
        when(refundRepository.findById(704L)).thenReturn(Optional.of(refund));
        StudentFees month1 = paidRow(1, BigDecimal.valueOf(2000), true);
        PaymentStudentFeesAllocation alloc = allocation(13L, PAYMENT_ID, month1.getId(), 1, 200000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of(alloc));
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(month1.getId())).thenReturn(200000L);
        when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(month1.getId())).thenReturn(200000L);

        var response = service.finalizeSuccessfulRefund(PAYMENT_ID, 704L, null, "admin", "ADMIN", "127.0.0.1");

        assertThat(response.get("providerRefundId")).isNull();
        assertThat(refund.getStatus()).isEqualTo(RefundSettlementService.STATUS_SUCCESS);
        assertThat(month1.getManualPaymentReceived()).isEqualByComparingTo("0");
    }

    @Test
    void finalizeSuccessfulRefund_alreadyFinalized_isIdempotent_doesNotReapplyReversal() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(705L, PAYMENT_ID, 100000L, "already done");
        refund.setStatus(RefundSettlementService.STATUS_SUCCESS); // finalized by an earlier call
        refund.setProviderRefundId("rfnd_already");
        when(refundRepository.findById(705L)).thenReturn(Optional.of(refund));

        var response = service.finalizeSuccessfulRefund(PAYMENT_ID, 705L, "rfnd_new_attempt", "admin", "ADMIN", "127.0.0.1");

        assertThat(response.get("providerRefundId")).isEqualTo("rfnd_already"); // untouched, not overwritten
        verify(paymentAllocationRepository, never()).findByPaymentIdOrderByMonthAsc(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void finalizeSuccessfulRefund_allocationLedgerInconsistency_throwsAndLeavesRefundPending() {
        Payment payment = razorpayPayment();
        payment.setRefundedAmountPaise(100000L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(706L, PAYMENT_ID, 100000L, "inconsistent");
        when(refundRepository.findById(706L)).thenReturn(Optional.of(refund));
        // An allocation exists but is already fully reversed by something else — the plan ends
        // up empty even though the payment-level ledger said there was room.
        StudentFees month1 = paidRow(1, BigDecimal.valueOf(2000), false);
        PaymentStudentFeesAllocation alloc = allocation(14L, PAYMENT_ID, month1.getId(), 1, 100000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of(alloc));
        when(allocationRefundRepository.sumAmountPaiseByAllocationId(14L)).thenReturn(100000L); // fully consumed already

        assertThatThrownBy(() -> service.finalizeSuccessfulRefund(PAYMENT_ID, 706L, "rfnd_confirmed", "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot reconcile");

        // The provider refund (rfnd_confirmed) already happened — the local row must stay
        // PENDING (a confirmed-but-unfinalized state needing manual reconciliation), never
        // silently marked FAILED or SUCCESS.
        assertThat(refund.getStatus()).isEqualTo(RefundSettlementService.STATUS_PENDING);
    }

    // ═══════════════════════════ markFailedAndRelease() ═══════════════════════════

    @Test
    void markFailedAndRelease_pendingRefund_releasesExactlyItsReservedAmount() {
        Payment payment = razorpayPayment();
        payment.setRefundedAmountPaise(150000L); // 100000 from this refund + 50000 from another
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(800L, PAYMENT_ID, 100000L, "will fail");
        when(refundRepository.findById(800L)).thenReturn(Optional.of(refund));

        service.markFailedAndRelease(PAYMENT_ID, 800L);

        assertThat(refund.getStatus()).isEqualTo(RefundSettlementService.STATUS_FAILED);
        assertThat(payment.getRefundedAmountPaise()).isEqualTo(50000L);
    }

    @Test
    void markFailedAndRelease_alreadyFailed_isNoOp_doesNotDoubleRelease() {
        Payment payment = razorpayPayment();
        payment.setRefundedAmountPaise(50000L); // already released once
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(801L, PAYMENT_ID, 100000L, "already failed");
        refund.setStatus(RefundSettlementService.STATUS_FAILED);
        when(refundRepository.findById(801L)).thenReturn(Optional.of(refund));

        service.markFailedAndRelease(PAYMENT_ID, 801L);

        assertThat(payment.getRefundedAmountPaise()).isEqualTo(50000L); // unchanged, not released twice
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void markFailedAndRelease_successfulRefund_isNeverReleased() {
        Payment payment = razorpayPayment();
        payment.setRefundedAmountPaise(100000L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(802L, PAYMENT_ID, 100000L, "succeeded");
        refund.setStatus(RefundSettlementService.STATUS_SUCCESS);
        when(refundRepository.findById(802L)).thenReturn(Optional.of(refund));

        service.markFailedAndRelease(PAYMENT_ID, 802L);

        assertThat(refund.getStatus()).isEqualTo(RefundSettlementService.STATUS_SUCCESS); // untouched
        assertThat(payment.getRefundedAmountPaise()).isEqualTo(100000L); // consumed capacity retained
        verify(paymentRepository, never()).save(any());
        verify(refundRepository, never()).save(any());
    }

    // ═══════════════════════════ Refund-Integrity Hardening, Phase C ═══════════════════════════
    // ── recordProviderRefundId() ─────────────────────────────────────────────────────────────

    @Test
    void recordProviderRefundId_pendingRefundWithoutOne_persistsIt() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(900L, PAYMENT_ID, 100000L, "slow");
        when(refundRepository.findById(900L)).thenReturn(Optional.of(refund));

        service.recordProviderRefundId(PAYMENT_ID, 900L, "rfnd_early");

        assertThat(refund.getProviderRefundId()).isEqualTo("rfnd_early");
    }

    @Test
    void recordProviderRefundId_alreadyHasOne_neverOverwritten() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(901L, PAYMENT_ID, 100000L, "slow");
        refund.setProviderRefundId("rfnd_original");
        when(refundRepository.findById(901L)).thenReturn(Optional.of(refund));

        service.recordProviderRefundId(PAYMENT_ID, 901L, "rfnd_different");

        assertThat(refund.getProviderRefundId()).isEqualTo("rfnd_original");
    }

    @Test
    void recordProviderRefundId_noLongerPending_isNoOp() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(902L, PAYMENT_ID, 100000L, "already done");
        refund.setStatus(RefundSettlementService.STATUS_SUCCESS);
        when(refundRepository.findById(902L)).thenReturn(Optional.of(refund));

        service.recordProviderRefundId(PAYMENT_ID, 902L, "rfnd_late");

        assertThat(refund.getProviderRefundId()).isNull();
    }

    // ── resolveFromProviderState() ───────────────────────────────────────────────────────────

    @Test
    void resolveFromProviderState_processed_finalizesAndKeepsCapacityConsumed() {
        Payment payment = razorpayPayment();
        payment.setMonth("100000000000");
        payment.setAmountPaid(200000);
        payment.setRefundedAmountPaise(200000L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(910L, PAYMENT_ID, 200000L, "full");
        refund.setProviderRefundId("rfnd_910");
        when(refundRepository.findByProviderRefundId("rfnd_910")).thenReturn(Optional.of(refund));
        when(refundRepository.findById(910L)).thenReturn(Optional.of(refund));
        StudentFees month1 = paidRow(1, BigDecimal.valueOf(2000), false);
        PaymentStudentFeesAllocation alloc = allocation(30L, PAYMENT_ID, month1.getId(), 1, 200000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of(alloc));
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(month1.getId())).thenReturn(200000L);
        when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(month1.getId())).thenReturn(200000L);

        var result = service.resolveFromProviderState("rfnd_910", "processed", "pay_razorpay123", 200000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.FINALIZED);
        assertThat(refund.getStatus()).isEqualTo(RefundSettlementService.STATUS_SUCCESS);
        assertThat(payment.getRefundedAmountPaise()).isEqualTo(200000L); // still consumed, not released
    }

    @Test
    void resolveFromProviderState_pending_noOpBeyondPersistingProviderIdIfMissing() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(911L, PAYMENT_ID, 100000L, "slow");
        when(refundRepository.findByProviderRefundId("rfnd_911")).thenReturn(Optional.of(refund));

        var result = service.resolveFromProviderState("rfnd_911", "pending", "pay_razorpay123", 100000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.NO_OP);
        assertThat(refund.getStatus()).isEqualTo(RefundSettlementService.STATUS_PENDING);
        assertThat(refund.getProviderRefundId()).isEqualTo("rfnd_911");
        verify(paymentAllocationRepository, never()).findByPaymentIdOrderByMonthAsc(any());
    }

    @Test
    void resolveFromProviderState_failed_releasesReservation() {
        Payment payment = razorpayPayment();
        payment.setRefundedAmountPaise(100000L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(912L, PAYMENT_ID, 100000L, "rejected");
        when(refundRepository.findByProviderRefundId("rfnd_912")).thenReturn(Optional.of(refund));
        when(refundRepository.findById(912L)).thenReturn(Optional.of(refund));

        var result = service.resolveFromProviderState("rfnd_912", "failed", "pay_razorpay123", 100000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.RELEASED);
        assertThat(refund.getStatus()).isEqualTo(RefundSettlementService.STATUS_FAILED);
        assertThat(payment.getRefundedAmountPaise()).isZero();
    }

    @Test
    void resolveFromProviderState_duplicateSuccess_secondCallIsNoOp() {
        Refund refund = pendingRefund(913L, PAYMENT_ID, 100000L, "done");
        refund.setStatus(RefundSettlementService.STATUS_SUCCESS);
        when(refundRepository.findByProviderRefundId("rfnd_913")).thenReturn(Optional.of(refund));

        var result = service.resolveFromProviderState("rfnd_913", "processed", "pay_razorpay123", 100000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.NO_OP);
        verify(paymentRepository, never()).findByIdForUpdate(any());
        verify(paymentAllocationRepository, never()).findByPaymentIdOrderByMonthAsc(any());
    }

    @Test
    void resolveFromProviderState_duplicateFailure_secondCallIsNoOp() {
        Refund refund = pendingRefund(914L, PAYMENT_ID, 100000L, "already failed");
        refund.setStatus(RefundSettlementService.STATUS_FAILED);
        when(refundRepository.findByProviderRefundId("rfnd_914")).thenReturn(Optional.of(refund));

        var result = service.resolveFromProviderState("rfnd_914", "failed", "pay_razorpay123", 100000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.NO_OP);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void resolveFromProviderState_paymentIdentityMismatch_rejectedNoMutation() {
        Payment payment = razorpayPayment(); // real paymentId = "pay_razorpay123"
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(915L, PAYMENT_ID, 100000L, "mismatch");
        when(refundRepository.findByProviderRefundId("rfnd_915")).thenReturn(Optional.of(refund));

        var result = service.resolveFromProviderState("rfnd_915", "processed", "pay_SOMEONE_ELSE", 100000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.REJECTED_MISMATCH);
        assertThat(refund.getStatus()).isEqualTo(RefundSettlementService.STATUS_PENDING); // untouched
        verify(paymentRepository, never()).save(any());
        verify(refundRepository, never()).save(any());
    }

    @Test
    void resolveFromProviderState_amountMismatch_rejectedNoMutation() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(916L, PAYMENT_ID, 100000L, "mismatch");
        when(refundRepository.findByProviderRefundId("rfnd_916")).thenReturn(Optional.of(refund));

        var result = service.resolveFromProviderState("rfnd_916", "processed", "pay_razorpay123", 999999L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.REJECTED_MISMATCH);
    }

    @Test
    void resolveFromProviderState_currencyMismatch_rejectedNoMutation() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(917L, PAYMENT_ID, 100000L, "mismatch");
        when(refundRepository.findByProviderRefundId("rfnd_917")).thenReturn(Optional.of(refund));

        var result = service.resolveFromProviderState("rfnd_917", "processed", "pay_razorpay123", 100000L, "USD",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.REJECTED_MISMATCH);
    }

    @Test
    void resolveFromProviderState_unknownProviderRefundId_noOp() {
        when(refundRepository.findByProviderRefundId("rfnd_unknown")).thenReturn(Optional.empty());

        var result = service.resolveFromProviderState("rfnd_unknown", "processed", "pay_razorpay123", 100000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.NO_OP);
        verify(paymentRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void resolveFromProviderState_unrecognizedStatus_conservativeNoOp() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        // A status this codebase has never seen documented anywhere — distinct from "reversed",
        // which is now a recognized (release-worthy) status; see the dedicated test below.
        Refund refund = pendingRefund(918L, PAYMENT_ID, 100000L, "some_future_status");
        when(refundRepository.findByProviderRefundId("rfnd_918")).thenReturn(Optional.of(refund));

        var result = service.resolveFromProviderState("rfnd_918", "some_future_status", "pay_razorpay123", 100000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.NO_OP);
        assertThat(refund.getStatus()).isEqualTo(RefundSettlementService.STATUS_PENDING);
        verify(paymentRepository, never()).save(any());
    }

    /** Phase D: researched against official Razorpay documentation (Refund Failures / refunds
     * FAQ) — a reversed refund's bank-side credit missed its ~48-hour window and the amount
     * returned to the merchant, meaning the customer never received it. Financially equivalent
     * to a failure for this refund's own reservation, so it releases capacity exactly like
     * "failed" — no longer a conservative no-op as it was in Phase C. */
    @Test
    void resolveFromProviderState_reversed_releasesReservationLikeFailed() {
        Payment payment = razorpayPayment();
        payment.setRefundedAmountPaise(100000L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(920L, PAYMENT_ID, 100000L, "bank credit failed");
        when(refundRepository.findByProviderRefundId("rfnd_920")).thenReturn(Optional.of(refund));
        when(refundRepository.findById(920L)).thenReturn(Optional.of(refund));

        var result = service.resolveFromProviderState("rfnd_920", "reversed", "pay_razorpay123", 100000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.RELEASED);
        assertThat(refund.getStatus()).isEqualTo(RefundSettlementService.STATUS_FAILED);
        assertThat(payment.getRefundedAmountPaise()).isZero();
    }

    @Test
    void resolveFromProviderState_reversedAfterAlreadySuccess_isNoOp_neverUnwindsATerminalSuccess() {
        Refund refund = pendingRefund(921L, PAYMENT_ID, 100000L, "already finalized");
        refund.setStatus(RefundSettlementService.STATUS_SUCCESS);
        when(refundRepository.findByProviderRefundId("rfnd_921")).thenReturn(Optional.of(refund));

        var result = service.resolveFromProviderState("rfnd_921", "reversed", "pay_razorpay123", 100000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.NO_OP);
        assertThat(refund.getStatus()).isEqualTo(RefundSettlementService.STATUS_SUCCESS); // never re-opened
        verify(paymentRepository, never()).findByIdForUpdate(any());
    }

    /** Task 11's exact scenario: provider succeeded (providerRefundId already persisted from an
     * earlier attempt), but the FIRST local finalize attempt failed (ledger inconsistency) and
     * rolled back, leaving the refund PENDING with a real providerRefundId. Reconciliation must
     * retry finalize only — never call createRefund again — and this time succeed once the
     * underlying data is fixed (a matching allocation now exists). */
    @Test
    void resolveFromProviderState_recoversAfterAnEarlierFinalizeFailure_retriesFinalizeOnly() {
        Payment payment = razorpayPayment();
        payment.setMonth("100000000000");
        payment.setAmountPaid(200000);
        payment.setRefundedAmountPaise(200000L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(919L, PAYMENT_ID, 200000L, "full");
        refund.setProviderRefundId("rfnd_919"); // already persisted from the earlier failed attempt
        when(refundRepository.findByProviderRefundId("rfnd_919")).thenReturn(Optional.of(refund));
        when(refundRepository.findById(919L)).thenReturn(Optional.of(refund));
        StudentFees month1 = paidRow(1, BigDecimal.valueOf(2000), false);
        PaymentStudentFeesAllocation alloc = allocation(31L, PAYMENT_ID, month1.getId(), 1, 200000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of(alloc));
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(month1.getId())).thenReturn(200000L);
        when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(month1.getId())).thenReturn(200000L);

        var result = service.resolveFromProviderState("rfnd_919", "processed", "pay_razorpay123", 200000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.FINALIZED);
        assertThat(refund.getStatus()).isEqualTo(RefundSettlementService.STATUS_SUCCESS);
        assertThat(refund.getProviderRefundId()).isEqualTo("rfnd_919"); // same id throughout, never re-created
    }

    @Test
    void markFailedAndRelease_neverDrivesLedgerNegative() {
        Payment payment = razorpayPayment();
        // Defensive scenario: reserved total is somehow already less than this refund's own
        // amount (should not happen in practice, but the release math must never go negative).
        payment.setRefundedAmountPaise(30000L);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        Refund refund = pendingRefund(803L, PAYMENT_ID, 100000L, "oversized relative to ledger");
        when(refundRepository.findById(803L)).thenReturn(Optional.of(refund));

        service.markFailedAndRelease(PAYMENT_ID, 803L);

        assertThat(payment.getRefundedAmountPaise()).isZero();
    }
}
