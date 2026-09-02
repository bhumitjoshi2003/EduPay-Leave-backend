package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.PaymentLineItemBreakdownDto;
import com.indraacademy.ias_management.dto.RefundRequest;
import com.indraacademy.ias_management.entity.AllocationRefund;
import com.indraacademy.ias_management.entity.LineItemType;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.PaymentStudentFeesAllocation;
import com.indraacademy.ias_management.entity.Refund;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentFeesLineItem;
import com.indraacademy.ias_management.repository.AllocationRefundRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.PaymentStudentFeesAllocationRepository;
import com.indraacademy.ias_management.repository.RefundRepository;
import com.indraacademy.ias_management.repository.StudentFeesLineItemRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Refund-correctness phase: PaymentService.processRefund is the sole path that reverses
 * StudentFees state for a refunded payment. Months to reverse are derived from the
 * persisted Payment.month bitmask (never the request body); a Refund row (append-only,
 * one per refund event) is the running ledger used to reject over-refunds/duplicates,
 * since Payment itself only ever held a blunt status string before this phase.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private RazorpayService razorpayService;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private PaymentStudentFeesAllocationRepository paymentAllocationRepository;
    @Mock private AllocationRefundRepository allocationRefundRepository;
    @Mock private FeeCalculationService feeCalculationService;
    @Mock private StudentFeesLineItemRepository studentFeesLineItemRepository;
    @Mock private BusinessNotificationService businessNotifications;

    private PaymentService service;

    private static final Long SCHOOL_ID = 1L;
    private static final Long PAYMENT_ID = 100L;

    @BeforeEach
    void setUp() {
        service = new PaymentService();
        ReflectionTestUtils.setField(service, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(service, "studentFeesRepository", studentFeesRepository);
        ReflectionTestUtils.setField(service, "refundRepository", refundRepository);
        ReflectionTestUtils.setField(service, "razorpayService", razorpayService);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "paymentAllocationRepository", paymentAllocationRepository);
        ReflectionTestUtils.setField(service, "allocationRefundRepository", allocationRefundRepository);
        ReflectionTestUtils.setField(service, "feeCalculationService", feeCalculationService);
        ReflectionTestUtils.setField(service, "studentFeesLineItemRepository", studentFeesLineItemRepository);
        ReflectionTestUtils.setField(service, "businessNotifications", businessNotifications);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        // Default: no allocation ledger for this payment (the pre-ledger/"legacy" case) —
        // tests that exercise the ledger-based path stub findByPaymentIdOrderByMonthAsc
        // explicitly to override this.
        lenient().when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(any())).thenReturn(List.of());
        lenient().when(allocationRefundRepository.sumAmountPaiseByAllocationId(any())).thenReturn(0L);
        lenient().when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(any())).thenReturn(0L);
        lenient().when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(any())).thenReturn(0L);

        // Mimic real JPA save-assigns-id behavior for Refund, matching the equivalent stub in
        // StudentFeesServiceTest for Payment — AllocationRefund rows need refund.getId().
        lenient().when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(nextRefundId++);
            }
            return r;
        });
    }

    private static long nextRefundId = 900L;

    /** A Razorpay-path payment covering academic months 1 and 2, ₹2000 (paid) each = ₹4000
     * total, 400000 paise. */
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
        // lenient: a month later in the payment's bitmask may never be looked up at all once
        // the refund pool is exhausted on an earlier month — that's expected, not a bug.
        // Legacy (bitmask-based) fallback locks via the ForUpdate finder; the allocation-based
        // path never looks a row up by student/year/month at all (it goes via studentFeesId).
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

    @Test
    void fullRefund_reversesBothCoveredMonthsAndMarksPaymentRefunded() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        StudentFees month1 = paidRow(1, BigDecimal.valueOf(2000), false);
        StudentFees month2 = paidRow(2, BigDecimal.valueOf(2000), false);
        when(razorpayService.createRefund(eq("pay_razorpay123"), eq(400000L), anyString()))
                .thenReturn(Map.of("refundId", "rfnd_1", "amount", 400000, "status", "processed"));

        Map<String, Object> result = service.processRefund(
                PAYMENT_ID, refundRequest(400000L, "Full withdrawal", null), "admin", "ADMIN", "127.0.0.1");

        assertThat(month1.getPaid()).isFalse();
        assertThat(month1.getAmountPaid()).isEqualByComparingTo("0");
        assertThat(month2.getPaid()).isFalse();
        assertThat(month2.getAmountPaid()).isEqualByComparingTo("0");
        assertThat(payment.getStatus()).isEqualTo("refunded");
        assertThat(result.get("monthsRefunded")).isEqualTo("110000000000");

        ArgumentCaptor<Refund> refundCaptor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getAmountPaise()).isEqualTo(400000L);
        assertThat(refundCaptor.getValue().getProviderRefundId()).isEqualTo("rfnd_1");
        verify(auditService).log(eq("admin"), eq("ADMIN"), eq("REFUND_PAYMENT"), eq("Payment"),
                eq(PAYMENT_ID.toString()), isNull(), anyString(), eq("127.0.0.1"));
    }

    @Test
    void partialRefund_reducesOnlyTheCoveredAmount_leavesMonthPaidWhenBalanceRemains() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        StudentFees month1 = paidRow(1, BigDecimal.valueOf(2000), false);
        StudentFees month2 = paidRow(2, BigDecimal.valueOf(2000), false);
        when(razorpayService.createRefund(eq("pay_razorpay123"), eq(150000L), anyString()))
                .thenReturn(Map.of("refundId", "rfnd_2"));

        Map<String, Object> result = service.processRefund(
                PAYMENT_ID, refundRequest(150000L, "Partial adjustment", null), "admin", "ADMIN", "10.0.0.1");

        // ₹1500 of ₹2000 refunded from month 1 only — month 1 still shows ₹500 paid and stays
        // marked paid; month 2 is untouched entirely (the refund pool was exhausted).
        assertThat(month1.getPaid()).isTrue();
        assertThat(month1.getAmountPaid()).isEqualByComparingTo("500");
        assertThat(month2.getPaid()).isTrue();
        assertThat(month2.getAmountPaid()).isEqualByComparingTo("2000");
        assertThat(payment.getStatus()).isEqualTo("partially_refunded");
        assertThat(result.get("monthsRefunded")).isEqualTo("100000000000");
    }

    @Test
    void secondPartialRefund_isBoundedByWhatsAlreadyBeenRefunded() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        paidRow(1, BigDecimal.valueOf(2000), false);
        paidRow(2, BigDecimal.valueOf(2000), false);
        // ₹3000 already refunded in a prior event; only ₹1000 (100000 paise) remains refundable.
        when(refundRepository.sumAmountPaiseByPaymentId(PAYMENT_ID)).thenReturn(300000L);

        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(150000L, "Too much", null), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100000");

        verify(razorpayService, never()).createRefund(any(), anyLong(), any());
        verify(refundRepository, never()).save(any());
    }

    @Test
    void alreadyFullyRefunded_rejectedBeforeAnyGatewayCall() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(refundRepository.sumAmountPaiseByPaymentId(PAYMENT_ID)).thenReturn(400000L); // == amountPaid

        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(1L, "duplicate attempt", null), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been fully refunded");

        verify(razorpayService, never()).createRefund(any(), anyLong(), any());
        verify(studentFeesRepository, never()).save(any());
    }

    @Test
    void duplicateIdempotencyKey_rejectedBeforeAnyGatewayCallOrMutation() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(refundRepository.existsByPaymentIdAndIdempotencyKey(PAYMENT_ID, "retry-key-1")).thenReturn(true);

        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(100000L, "retry", "retry-key-1"), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idempotency");

        verify(razorpayService, never()).createRefund(any(), anyLong(), any());
        verify(refundRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void crossSchoolPayment_rejectedAsNotFound_neverLeaksAnotherSchoolsPayment() {
        Payment payment = razorpayPayment();
        payment.setSchoolId(2L); // belongs to a different school than SCHOOL_ID(1L)
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(100000L, "cross-school attempt", null), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(NoSuchElementException.class);

        verify(razorpayService, never()).createRefund(any(), anyLong(), any());
    }

    @Test
    void nonexistentPayment_rejectedAsNotFound() {
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(100000L, "no such payment", null), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void failedProviderRefund_leavesStudentFeesAndPaymentUntouched() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        paidRow(1, BigDecimal.valueOf(2000), false);
        paidRow(2, BigDecimal.valueOf(2000), false);
        when(razorpayService.createRefund(anyString(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("Razorpay refund failed: gateway timeout"));

        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(400000L, "gateway will fail", null), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("gateway timeout");

        // Nothing was mutated: the gateway call happens before any StudentFees/Refund/Payment
        // write, so a provider-side failure never leaves a half-applied refund on record.
        verify(studentFeesRepository, never()).save(any());
        verify(refundRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), eq("REFUND_PAYMENT"), any(), any(), any(), any(), any());
        assertThat(payment.getStatus()).isEqualTo("success"); // unchanged
    }

    @Test
    void manualPayment_refundSkipsGatewayCall_stillReversesStudentFees() {
        Payment payment = manualPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        StudentFees month1 = paidRow(1, BigDecimal.valueOf(2000), true);
        paidRow(2, BigDecimal.valueOf(2000), true);

        Map<String, Object> result = service.processRefund(
                PAYMENT_ID, refundRequest(200000L, "cash refund", null), "admin", "ADMIN", "127.0.0.1");

        verify(razorpayService, never()).createRefund(any(), anyLong(), any());
        assertThat(month1.getPaid()).isFalse();
        assertThat(month1.getManualPaymentReceived()).isEqualByComparingTo("0");
        assertThat(result.get("providerRefundId")).isNull();
    }

    @Test
    void monthWithNoRemainingPaidState_isSkippedRatherThanFabricated() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        // Month 1 was never actually marked paid (defensive/anomalous state) — the refund
        // must not invent a reversal for it.
        StudentFees month1 = new StudentFees();
        month1.setStudentId("S1");
        month1.setSchoolId(SCHOOL_ID);
        month1.setYear("2025-2026");
        month1.setMonth(1);
        month1.setPaid(false);
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndYearAndMonthForUpdate("S1", SCHOOL_ID, "2025-2026", 1))
                .thenReturn(month1);
        StudentFees month2 = paidRow(2, BigDecimal.valueOf(2000), false);
        when(razorpayService.createRefund(anyString(), anyLong(), anyString())).thenReturn(Map.of("refundId", "rfnd_3"));

        Map<String, Object> result = service.processRefund(
                PAYMENT_ID, refundRequest(150000L, "refund", null), "admin", "ADMIN", "127.0.0.1");

        // Only month 2 (the one whose state actually supports reversal) is touched.
        assertThat(result.get("monthsRefunded")).isEqualTo("010000000000");
        assertThat(month2.getPaid()).isTrue(); // ₹1500 of ₹2000 refunded, ₹500 remains
        assertThat(month2.getAmountPaid()).isEqualByComparingTo("500");
    }

    @Test
    void originalFeeSnapshotFieldsAreNeverTouchedByARefund() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        StudentFees month1 = paidRow(1, BigDecimal.valueOf(2000), false);
        month1.setBaseAmountDue(BigDecimal.valueOf(1800));
        month1.setBusFeeDue(BigDecimal.valueOf(200));
        month1.setDiscountAmount(BigDecimal.valueOf(50));
        month1.setAmountRuleSnapshot("{\"feeHeads\":[]}");
        StudentFees month2 = paidRow(2, BigDecimal.valueOf(2000), false);
        when(razorpayService.createRefund(anyString(), anyLong(), anyString())).thenReturn(Map.of("refundId", "rfnd_4"));

        service.processRefund(PAYMENT_ID, refundRequest(400000L, "full", null), "admin", "ADMIN", "127.0.0.1");

        assertThat(month1.getBaseAmountDue()).isEqualByComparingTo("1800");
        assertThat(month1.getBusFeeDue()).isEqualByComparingTo("200");
        assertThat(month1.getDiscountAmount()).isEqualByComparingTo("50");
        assertThat(month1.getAmountRuleSnapshot()).isEqualTo("{\"feeHeads\":[]}");
    }

    // ─── Allocation-ledger-based refund — the primary path for any payment recorded after ──
    // ─── this ledger was introduced (i.e. has allocation rows at all)                    ───

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
    void ledgerBasedRefund_singlePaymentSingleMonth_fullRefund_reversesExactAllocation() {
        Payment payment = razorpayPayment();
        payment.setMonth("100000000000");
        payment.setAmountPaid(200000); // ₹2000
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        StudentFees month1 = paidRow(1, BigDecimal.valueOf(2000), false);
        PaymentStudentFeesAllocation alloc = allocation(10L, PAYMENT_ID, month1.getId(), 1, 200000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of(alloc));
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(month1.getId())).thenReturn(200000L);
        when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(month1.getId())).thenReturn(200000L);
        when(razorpayService.createRefund(eq("pay_razorpay123"), eq(200000L), anyString()))
                .thenReturn(Map.of("refundId", "rfnd_led_1"));

        Map<String, Object> result = service.processRefund(
                PAYMENT_ID, refundRequest(200000L, "full ledger refund", null), "admin", "ADMIN", "127.0.0.1");

        assertThat(result.get("legacyApproximation")).isEqualTo(false);
        assertThat(month1.getPaid()).isFalse();
        assertThat(month1.getAmountPaid()).isEqualByComparingTo("0");
        ArgumentCaptor<AllocationRefund> arCaptor = ArgumentCaptor.forClass(AllocationRefund.class);
        verify(allocationRefundRepository).save(arCaptor.capture());
        assertThat(arCaptor.getValue().getAllocationId()).isEqualTo(10L);
        assertThat(arCaptor.getValue().getAmountPaise()).isEqualTo(200000L);
        assertThat(arCaptor.getValue().getStudentFeesId()).isEqualTo(month1.getId());
    }

    @Test
    void ledgerBasedRefund_onePaymentMultipleMonths_fullRefund_reversesEachAllocation() {
        Payment payment = razorpayPayment(); // month="110000000000", amountPaid=400000
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        StudentFees m1 = paidRow(1, BigDecimal.valueOf(2000), false);
        StudentFees m2 = paidRow(2, BigDecimal.valueOf(2000), false);
        PaymentStudentFeesAllocation a1 = allocation(11L, PAYMENT_ID, m1.getId(), 1, 200000L);
        PaymentStudentFeesAllocation a2 = allocation(12L, PAYMENT_ID, m2.getId(), 2, 200000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of(a1, a2));
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(m1.getId())).thenReturn(200000L);
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(m2.getId())).thenReturn(200000L);
        when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(m1.getId())).thenReturn(200000L);
        when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(m2.getId())).thenReturn(200000L);
        when(razorpayService.createRefund(eq("pay_razorpay123"), eq(400000L), anyString())).thenReturn(Map.of("refundId", "rfnd_led_2"));

        Map<String, Object> result = service.processRefund(
                PAYMENT_ID, refundRequest(400000L, "full", null), "admin", "ADMIN", "127.0.0.1");

        assertThat(m1.getPaid()).isFalse();
        assertThat(m2.getPaid()).isFalse();
        verify(allocationRefundRepository, times(2)).save(any(AllocationRefund.class));
        assertThat(result.get("monthsRefunded")).isEqualTo("110000000000");
    }

    @Test
    void ledgerBasedRefund_partialRefund_canFlipAPreviouslyFullyPaidMonthBackToUnpaid() {
        Payment payment = razorpayPayment();
        payment.setMonth("100000000000");
        payment.setAmountPaid(200000);
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        StudentFees m1 = paidRow(1, BigDecimal.valueOf(2000), false); // due = 2000
        PaymentStudentFeesAllocation a1 = allocation(13L, PAYMENT_ID, m1.getId(), 1, 200000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of(a1));
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(m1.getId())).thenReturn(200000L);
        // Only ₹500 reversed so far — net remaining = ₹1500, below the ₹2000 due.
        when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(m1.getId())).thenReturn(50000L);
        when(razorpayService.createRefund(anyString(), eq(50000L), anyString())).thenReturn(Map.of("refundId", "rfnd_led_3"));

        service.processRefund(PAYMENT_ID, refundRequest(50000L, "partial", null), "admin", "ADMIN", "127.0.0.1");

        assertThat(m1.getAmountPaid()).isEqualByComparingTo("1500");
        assertThat(m1.getPaid()).isFalse(); // no longer fully covers the ₹2000 due
    }

    @Test
    void ledgerBasedRefund_multiplePartialRefunds_accumulateAgainstTheSameAllocation() {
        Payment payment = razorpayPayment();
        payment.setMonth("100000000000");
        payment.setAmountPaid(200000); // ₹2000
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        StudentFees m1 = paidRow(1, BigDecimal.valueOf(2000), false);
        PaymentStudentFeesAllocation a1 = allocation(14L, PAYMENT_ID, m1.getId(), 1, 200000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of(a1));
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(m1.getId())).thenReturn(200000L);
        when(razorpayService.createRefund(anyString(), anyLong(), anyString())).thenReturn(Map.of("refundId", "rfnd_multi"));

        // First partial refund: ₹500.
        when(allocationRefundRepository.sumAmountPaiseByAllocationId(14L)).thenReturn(0L);
        when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(m1.getId())).thenReturn(50000L);
        when(refundRepository.sumAmountPaiseByPaymentId(PAYMENT_ID)).thenReturn(0L);
        service.processRefund(PAYMENT_ID, refundRequest(50000L, "first partial", "key-1"), "admin", "ADMIN", "127.0.0.1");
        assertThat(m1.getAmountPaid()).isEqualByComparingTo("1500");

        // Second partial refund: another ₹500 — bounded by what's left on this allocation
        // (₹1500 remaining after the first ₹500), which comfortably covers it.
        when(allocationRefundRepository.sumAmountPaiseByAllocationId(14L)).thenReturn(50000L);
        when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(m1.getId())).thenReturn(100000L);
        when(refundRepository.sumAmountPaiseByPaymentId(PAYMENT_ID)).thenReturn(50000L);
        service.processRefund(PAYMENT_ID, refundRequest(50000L, "second partial", "key-2"), "admin", "ADMIN", "127.0.0.1");

        assertThat(m1.getAmountPaid()).isEqualByComparingTo("1000"); // 2000 - 500 - 500
        verify(allocationRefundRepository, times(2)).save(any(AllocationRefund.class));
    }

    @Test
    void refundAfterMultiplePaymentsAgainstSameMonth_onlyReversesTheRefundedPaymentsOwnAllocation() {
        // The scenario the allocation ledger exists to get right: two DIFFERENT payments both
        // contributed to the same StudentFees row. Refunding one must not touch the other's
        // money — something the old Payment.month-guessing approach could never do safely.
        Long paymentAId = 200L;
        Payment paymentA = razorpayPayment();
        paymentA.setId(paymentAId);
        paymentA.setMonth("100000000000");
        paymentA.setAmountPaid(100000); // ₹1000
        when(paymentRepository.findByIdForUpdate(paymentAId)).thenReturn(Optional.of(paymentA));

        StudentFees m1 = paidRow(1, BigDecimal.valueOf(2000), false); // due = 2000
        PaymentStudentFeesAllocation allocA = allocation(20L, paymentAId, m1.getId(), 1, 100000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(paymentAId)).thenReturn(List.of(allocA));
        // Gross allocated to this row across BOTH payment A and payment B (not refunded here)
        // is ₹2000 — that's what made the row fully paid before this refund.
        when(paymentAllocationRepository.sumAmountPaiseByStudentFeesId(m1.getId())).thenReturn(200000L);
        // Only payment A's ₹1000 allocation gets reversed by this refund.
        when(allocationRefundRepository.sumAmountPaiseByStudentFeesId(m1.getId())).thenReturn(100000L);
        when(razorpayService.createRefund(anyString(), eq(100000L), anyString())).thenReturn(Map.of("refundId", "rfnd_multi_pay"));

        service.processRefund(paymentAId, refundRequest(100000L, "refund payment A only", null), "admin", "ADMIN", "127.0.0.1");

        // Net = 200000 (gross, from both payments) - 100000 (only A's share reversed) = ₹1000
        // — payment B's ₹1000 contribution is untouched, never zeroed out.
        assertThat(m1.getAmountPaid()).isEqualByComparingTo("1000");
        assertThat(m1.getPaid()).isFalse(); // ₹1000 no longer covers the ₹2000 due
    }

    @Test
    void concurrentRefundAttempts_secondRefundCorrectlyBoundedByFirstsAlreadyCommittedTotal() {
        // True concurrent-thread/real-DB-lock testing isn't possible in this Mockito unit-test
        // suite (no Testcontainers/transactional DB here). What IS verified: (a) the payment
        // row is fetched via the pessimistic-lock finder for every refund attempt — the
        // mechanism a real DB uses to serialize two concurrent transactions against the same
        // row; and (b) GIVEN that serialization, two sequential attempts correctly compose —
        // the second sees the first's committed total and is rejected once it would exceed
        // what's left, rather than both succeeding independently and over-refunding.
        Payment payment = razorpayPayment(); // amountPaid = 400000, months 1+2
        when(paymentRepository.findByIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(payment));
        paidRow(1, BigDecimal.valueOf(2000), false);
        paidRow(2, BigDecimal.valueOf(2000), false);
        when(razorpayService.createRefund(anyString(), anyLong(), anyString())).thenReturn(Map.of("refundId", "rfnd_c1"));

        when(refundRepository.sumAmountPaiseByPaymentId(PAYMENT_ID)).thenReturn(0L);
        service.processRefund(PAYMENT_ID, refundRequest(300000L, "first", null), "admin", "ADMIN", "127.0.0.1");
        verify(paymentRepository).findByIdForUpdate(PAYMENT_ID);

        when(refundRepository.sumAmountPaiseByPaymentId(PAYMENT_ID)).thenReturn(300000L);
        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(200000L, "second, too much", null), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100000");

        verify(paymentRepository, times(2)).findByIdForUpdate(PAYMENT_ID);
    }

    // ─── getPaymentLineItemBreakdown — Phase 4 receipt/payment-detail line-item alignment ──

    private StudentFeesLineItem lineItem(Long studentFeesId, LineItemType type, String feeHeadName, long grossPaise, long discountPaise) {
        StudentFeesLineItem li = new StudentFeesLineItem();
        li.setStudentFeesId(studentFeesId);
        li.setSchoolId(SCHOOL_ID);
        li.setStudentId("S1");
        li.setSession("2025-2026");
        li.setLineItemType(type);
        li.setFeeHeadName(feeHeadName);
        li.setGrossAmountPaise(grossPaise);
        li.setDiscountAmountPaise(discountPaise);
        li.setNetAmountPaise(grossPaise - discountPaise);
        return li;
    }

    @Test
    void getPaymentLineItemBreakdown_allMonthsHaveLineItems_aggregatesAcrossMonthsAndReconciles() {
        Payment payment = razorpayPayment(); // months 1+2, amountPaid=400000
        when(paymentRepository.findByPaymentIdAndSchoolId("pay_razorpay123", SCHOOL_ID)).thenReturn(Optional.of(payment));
        StudentFees m1 = paidRow(1, BigDecimal.valueOf(2000), false);
        StudentFees m2 = paidRow(2, BigDecimal.valueOf(2000), false);
        when(studentFeesRepository.findById(m1.getId())).thenReturn(Optional.of(m1));
        when(studentFeesRepository.findById(m2.getId())).thenReturn(Optional.of(m2));
        PaymentStudentFeesAllocation a1 = allocation(21L, PAYMENT_ID, m1.getId(), 1, 200000L);
        PaymentStudentFeesAllocation a2 = allocation(22L, PAYMENT_ID, m2.getId(), 2, 200000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of(a1, a2));

        when(studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(m1.getId())).thenReturn(List.of(
                lineItem(m1.getId(), LineItemType.FEE_HEAD, "Tuition Fee", 150000L, 0L),
                lineItem(m1.getId(), LineItemType.BUS, "Bus Fee", 50000L, 0L)
        ));
        when(studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(m2.getId())).thenReturn(List.of(
                lineItem(m2.getId(), LineItemType.FEE_HEAD, "Tuition Fee", 150000L, 0L),
                lineItem(m2.getId(), LineItemType.BUS, "Bus Fee", 50000L, 0L)
        ));

        PaymentLineItemBreakdownDto dto = service.getPaymentLineItemBreakdown("pay_razorpay123").orElseThrow();

        assertThat(dto.isLineItemBreakdownAvailable()).isTrue();
        assertThat(dto.getLineItems()).hasSize(2);
        assertThat(dto.getLineItems()).anySatisfy(li -> {
            assertThat(li.getFeeHeadName()).isEqualTo("Tuition Fee");
            assertThat(li.getNetAmount()).isEqualByComparingTo("3000"); // 1500 + 1500 across both months
        });
        assertThat(dto.getLineItems()).anySatisfy(li -> {
            assertThat(li.getFeeHeadName()).isEqualTo("Bus Fee");
            assertThat(li.getNetAmount()).isEqualByComparingTo("1000"); // 500 + 500
        });

        // The exact reconciliation invariant: SUM(lineItems.netAmount) == totalSchoolFeeDue,
        // which itself equals resolveSchoolFeeDue summed across the covered months (2000+2000).
        BigDecimal lineItemTotal = dto.getLineItems().stream()
                .map(li -> li.getNetAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(lineItemTotal).isEqualByComparingTo(dto.getTotalSchoolFeeDue());
        assertThat(dto.getTotalSchoolFeeDue()).isEqualByComparingTo("4000");
    }

    @Test
    void getPaymentLineItemBreakdown_oneMonthMissingLineItems_fallsBackToTrustedTotalNeverPartial() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByPaymentIdAndSchoolId("pay_razorpay123", SCHOOL_ID)).thenReturn(Optional.of(payment));
        StudentFees m1 = paidRow(1, BigDecimal.valueOf(2000), false);
        StudentFees m2 = paidRow(2, BigDecimal.valueOf(2000), false);
        when(studentFeesRepository.findById(m1.getId())).thenReturn(Optional.of(m1));
        when(studentFeesRepository.findById(m2.getId())).thenReturn(Optional.of(m2));
        PaymentStudentFeesAllocation a1 = allocation(23L, PAYMENT_ID, m1.getId(), 1, 200000L);
        PaymentStudentFeesAllocation a2 = allocation(24L, PAYMENT_ID, m2.getId(), 2, 200000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of(a1, a2));

        when(studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(m1.getId())).thenReturn(List.of(
                lineItem(m1.getId(), LineItemType.FEE_HEAD, "Tuition Fee", 200000L, 0L)
        ));
        // Month 2 predates the line-item phase — no rows at all.
        when(studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(m2.getId())).thenReturn(List.of());

        PaymentLineItemBreakdownDto dto = service.getPaymentLineItemBreakdown("pay_razorpay123").orElseThrow();

        assertThat(dto.isLineItemBreakdownAvailable()).isFalse();
        assertThat(dto.getLineItems()).isEmpty(); // never a partial/invented composition
        assertThat(dto.getTotalSchoolFeeDue()).isEqualByComparingTo("4000"); // trusted total still shown
    }

    @Test
    void getPaymentLineItemBreakdown_noAllocationRows_historicalPayment_fullFallbackNoInventedCategories() {
        Payment payment = razorpayPayment();
        when(paymentRepository.findByPaymentIdAndSchoolId("pay_razorpay123", SCHOOL_ID)).thenReturn(Optional.of(payment));
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of());

        PaymentLineItemBreakdownDto dto = service.getPaymentLineItemBreakdown("pay_razorpay123").orElseThrow();

        assertThat(dto.isLineItemBreakdownAvailable()).isFalse();
        assertThat(dto.getLineItems()).isEmpty();
        assertThat(dto.getTotalSchoolFeeDue()).isNull();
        verifyNoInteractions(studentFeesLineItemRepository);
    }

    @Test
    void getPaymentLineItemBreakdown_paymentNotFound_returnsEmpty() {
        when(paymentRepository.findByPaymentIdAndSchoolId("nope", SCHOOL_ID)).thenReturn(Optional.empty());

        assertThat(service.getPaymentLineItemBreakdown("nope")).isEmpty();
    }
}
