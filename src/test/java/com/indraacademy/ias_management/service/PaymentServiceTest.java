package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PaymentLineItemBreakdownDto;
import com.indraacademy.ias_management.dto.RefundRequest;
import com.indraacademy.ias_management.entity.LineItemType;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.PaymentStudentFeesAllocation;
import com.indraacademy.ias_management.entity.Refund;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentFeesLineItem;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.PaymentStudentFeesAllocationRepository;
import com.indraacademy.ias_management.repository.StudentFeesLineItemRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * PaymentService.processRefund is now a thin Reserve/Call/Finalize orchestrator over
 * {@link RefundSettlementService} (Refund-Integrity Hardening, Phase B) — the reservation and
 * finalization logic itself, and its own focused tests, live in RefundSettlementServiceTest.
 * This file covers only the orchestration: does processRefund call reserve() first, does it
 * skip/attempt the gateway call correctly, does it route each ReservationOutcome to the exact
 * exception/response the pre-Phase-B code produced for the equivalent situation, and does an
 * ambiguous provider outcome leave the refund PENDING instead of throwing.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private RazorpayService razorpayService;
    @Mock private SecurityUtil securityUtil;
    @Mock private PaymentStudentFeesAllocationRepository paymentAllocationRepository;
    @Mock private FeeCalculationService feeCalculationService;
    @Mock private StudentFeesLineItemRepository studentFeesLineItemRepository;
    @Mock private RefundSettlementService refundSettlementService;

    private PaymentService service;

    private static final Long SCHOOL_ID = 1L;
    private static final Long PAYMENT_ID = 100L;

    @BeforeEach
    void setUp() {
        service = new PaymentService();
        ReflectionTestUtils.setField(service, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(service, "studentFeesRepository", studentFeesRepository);
        ReflectionTestUtils.setField(service, "razorpayService", razorpayService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "paymentAllocationRepository", paymentAllocationRepository);
        ReflectionTestUtils.setField(service, "feeCalculationService", feeCalculationService);
        ReflectionTestUtils.setField(service, "studentFeesLineItemRepository", studentFeesLineItemRepository);
        ReflectionTestUtils.setField(service, "refundSettlementService", refundSettlementService);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
    }

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

    private Refund pendingRefund(Long id, long amountPaise, String reason) {
        Refund r = new Refund();
        r.setId(id);
        r.setPaymentId(PAYMENT_ID);
        r.setAmountPaise(amountPaise);
        r.setReason(reason);
        r.setStatus(RefundSettlementService.STATUS_PENDING);
        r.setProviderIdempotencyKey("key-" + id);
        return r;
    }

    private RefundRequest refundRequest(long amountPaise, String reason, String idempotencyKey) {
        RefundRequest req = new RefundRequest();
        req.setAmount(amountPaise);
        req.setReason(reason);
        req.setIdempotencyKey(idempotencyKey);
        return req;
    }

    // ═══════════════════════════ RESERVED → provider call → finalize ═══════════════════════════

    @Test
    void reserved_razorpayPayment_callsGatewayThenFinalizes() {
        Payment payment = razorpayPayment();
        Refund refund = pendingRefund(700L, 400000L, "full");
        when(refundSettlementService.reserve(eq(PAYMENT_ID), any(), eq(SCHOOL_ID)))
                .thenReturn(new RefundSettlementService.ReservationResult(
                        RefundSettlementService.ReservationOutcome.RESERVED, "ok", refund, payment));
        when(razorpayService.createRefund("pay_razorpay123", 400000L, "full"))
                .thenReturn(new RazorpayService.ProviderRefundResult("rfnd_1", RazorpayService.PROVIDER_STATUS_PROCESSED, "pay_razorpay123", 400000L, "INR"));
        when(refundSettlementService.finalizeSuccessfulRefund(PAYMENT_ID, 700L, "rfnd_1", "admin", "ADMIN", "127.0.0.1"))
                .thenReturn(Map.of("refundId", 700L, "status", "success"));

        Map<String, Object> result = service.processRefund(
                PAYMENT_ID, refundRequest(400000L, "full", null), "admin", "ADMIN", "127.0.0.1");

        assertThat(result.get("status")).isEqualTo("success");
        verify(razorpayService).createRefund("pay_razorpay123", 400000L, "full");
        verify(refundSettlementService).finalizeSuccessfulRefund(PAYMENT_ID, 700L, "rfnd_1", "admin", "ADMIN", "127.0.0.1");
    }

    @Test
    void reserved_manualPayment_skipsGatewayCall_stillFinalizes() {
        Payment payment = manualPayment();
        Refund refund = pendingRefund(701L, 200000L, "cash refund");
        when(refundSettlementService.reserve(eq(PAYMENT_ID), any(), eq(SCHOOL_ID)))
                .thenReturn(new RefundSettlementService.ReservationResult(
                        RefundSettlementService.ReservationOutcome.RESERVED, "ok", refund, payment));
        Map<String, Object> finalizeResponse = new java.util.LinkedHashMap<>();
        finalizeResponse.put("refundId", 701L);
        finalizeResponse.put("status", "success");
        finalizeResponse.put("providerRefundId", null);
        when(refundSettlementService.finalizeSuccessfulRefund(PAYMENT_ID, 701L, null, "admin", "ADMIN", "127.0.0.1"))
                .thenReturn(finalizeResponse);

        Map<String, Object> result = service.processRefund(
                PAYMENT_ID, refundRequest(200000L, "cash refund", null), "admin", "ADMIN", "127.0.0.1");

        verify(razorpayService, never()).createRefund(any(), anyLong(), any());
        verify(refundSettlementService).finalizeSuccessfulRefund(PAYMENT_ID, 701L, null, "admin", "ADMIN", "127.0.0.1");
        assertThat(result.get("providerRefundId")).isNull();
    }

    @Test
    void reserved_ambiguousProviderOutcome_returnsPendingResponse_doesNotThrow_doesNotFinalize() {
        Payment payment = razorpayPayment();
        Refund refund = pendingRefund(702L, 400000L, "will time out");
        when(refundSettlementService.reserve(eq(PAYMENT_ID), any(), eq(SCHOOL_ID)))
                .thenReturn(new RefundSettlementService.ReservationResult(
                        RefundSettlementService.ReservationOutcome.RESERVED, "ok", refund, payment));
        when(razorpayService.createRefund(anyString(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("Refund failed: connection timed out"));

        Map<String, Object> result = service.processRefund(
                PAYMENT_ID, refundRequest(400000L, "will time out", null), "admin", "ADMIN", "127.0.0.1");

        assertThat(result.get("status")).isEqualTo("pending");
        assertThat(result.get("refundId")).isEqualTo(702L);
        verify(refundSettlementService, never()).finalizeSuccessfulRefund(any(), any(), any(), any(), any(), any());
        verify(refundSettlementService, never()).markFailedAndRelease(any(), any());
    }

    @Test
    void reserved_providerReturnsPending_persistsProviderIdKeepsPending_doesNotFinalize() {
        Payment payment = razorpayPayment();
        Refund refund = pendingRefund(709L, 400000L, "slow refund");
        when(refundSettlementService.reserve(eq(PAYMENT_ID), any(), eq(SCHOOL_ID)))
                .thenReturn(new RefundSettlementService.ReservationResult(
                        RefundSettlementService.ReservationOutcome.RESERVED, "ok", refund, payment));
        when(razorpayService.createRefund(anyString(), anyLong(), anyString()))
                .thenReturn(new RazorpayService.ProviderRefundResult("rfnd_pending", RazorpayService.PROVIDER_STATUS_PENDING, "pay_razorpay123", 400000L, "INR"));

        Map<String, Object> result = service.processRefund(
                PAYMENT_ID, refundRequest(400000L, "slow refund", null), "admin", "ADMIN", "127.0.0.1");

        assertThat(result.get("status")).isEqualTo("pending");
        assertThat(result.get("providerRefundId")).isEqualTo("rfnd_pending");
        verify(refundSettlementService).recordProviderRefundId(PAYMENT_ID, 709L, "rfnd_pending");
        verify(refundSettlementService, never()).finalizeSuccessfulRefund(any(), any(), any(), any(), any(), any());
    }

    @Test
    void reserved_providerReturnsDefinitiveFailure_releasesReservation_throws() {
        Payment payment = razorpayPayment();
        Refund refund = pendingRefund(710L, 400000L, "rejected refund");
        when(refundSettlementService.reserve(eq(PAYMENT_ID), any(), eq(SCHOOL_ID)))
                .thenReturn(new RefundSettlementService.ReservationResult(
                        RefundSettlementService.ReservationOutcome.RESERVED, "ok", refund, payment));
        when(razorpayService.createRefund(anyString(), anyLong(), anyString()))
                .thenReturn(new RazorpayService.ProviderRefundResult("rfnd_failed", RazorpayService.PROVIDER_STATUS_FAILED, "pay_razorpay123", 400000L, "INR"));

        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(400000L, "rejected refund", null), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rejected");

        verify(refundSettlementService).markFailedAndRelease(PAYMENT_ID, 710L);
        verify(refundSettlementService, never()).finalizeSuccessfulRefund(any(), any(), any(), any(), any(), any());
    }

    @Test
    void reserved_providerReturnsProcessed_persistsProviderIdBeforeFinalizing() {
        Payment payment = razorpayPayment();
        Refund refund = pendingRefund(711L, 400000L, "full");
        when(refundSettlementService.reserve(eq(PAYMENT_ID), any(), eq(SCHOOL_ID)))
                .thenReturn(new RefundSettlementService.ReservationResult(
                        RefundSettlementService.ReservationOutcome.RESERVED, "ok", refund, payment));
        when(razorpayService.createRefund(anyString(), anyLong(), anyString()))
                .thenReturn(new RazorpayService.ProviderRefundResult("rfnd_ok", RazorpayService.PROVIDER_STATUS_PROCESSED, "pay_razorpay123", 400000L, "INR"));
        when(refundSettlementService.finalizeSuccessfulRefund(PAYMENT_ID, 711L, "rfnd_ok", "admin", "ADMIN", "127.0.0.1"))
                .thenReturn(Map.of("refundId", 711L, "status", "success"));

        service.processRefund(PAYMENT_ID, refundRequest(400000L, "full", null), "admin", "ADMIN", "127.0.0.1");

        var inOrder = inOrder(refundSettlementService);
        inOrder.verify(refundSettlementService).recordProviderRefundId(PAYMENT_ID, 711L, "rfnd_ok");
        inOrder.verify(refundSettlementService).finalizeSuccessfulRefund(PAYMENT_ID, 711L, "rfnd_ok", "admin", "ADMIN", "127.0.0.1");
    }

    @Test
    void reserved_finalizeThrowsAfterProviderSuccess_exceptionPropagates_notSwallowed() {
        Payment payment = razorpayPayment();
        Refund refund = pendingRefund(703L, 400000L, "ledger inconsistency");
        when(refundSettlementService.reserve(eq(PAYMENT_ID), any(), eq(SCHOOL_ID)))
                .thenReturn(new RefundSettlementService.ReservationResult(
                        RefundSettlementService.ReservationOutcome.RESERVED, "ok", refund, payment));
        when(razorpayService.createRefund(anyString(), anyLong(), anyString()))
                .thenReturn(new RazorpayService.ProviderRefundResult("rfnd_confirmed", RazorpayService.PROVIDER_STATUS_PROCESSED, "pay_razorpay123", 400000L, "INR"));
        when(refundSettlementService.finalizeSuccessfulRefund(eq(PAYMENT_ID), eq(703L), eq("rfnd_confirmed"), any(), any(), any()))
                .thenThrow(new IllegalStateException("Cannot reconcile refund amount against this payment's allocation ledger."));

        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(400000L, "ledger inconsistency", null), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot reconcile");
    }

    // ═══════════════════════════ REJECTED → exact pre-Phase-B exception types ═══════════════════

    @Test
    void rejected_paymentNotFound_throwsNoSuchElementException() {
        when(refundSettlementService.reserve(eq(PAYMENT_ID), any(), eq(SCHOOL_ID)))
                .thenReturn(RefundSettlementService.ReservationResult.rejected("Payment not found."));

        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(1000L, "no such payment", null), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(NoSuchElementException.class);

        verify(razorpayService, never()).createRefund(any(), anyLong(), any());
    }

    @Test
    void rejected_exceedsRemaining_throwsIllegalArgumentException() {
        when(refundSettlementService.reserve(eq(PAYMENT_ID), any(), eq(SCHOOL_ID)))
                .thenReturn(RefundSettlementService.ReservationResult.rejected(
                        "Refund amount exceeds the remaining refundable balance (100000 paise)."));

        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(150000L, "too much", null), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100000");
    }

    @Test
    void rejected_alreadyFullyRefunded_throwsIllegalStateException() {
        when(refundSettlementService.reserve(eq(PAYMENT_ID), any(), eq(SCHOOL_ID)))
                .thenReturn(RefundSettlementService.ReservationResult.rejected("This payment has already been fully refunded."));

        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(1L, "duplicate attempt", null), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been fully refunded");
    }

    @Test
    void rejected_noRazorpayPaymentId_throwsIllegalStateException() {
        when(refundSettlementService.reserve(eq(PAYMENT_ID), any(), eq(SCHOOL_ID)))
                .thenReturn(RefundSettlementService.ReservationResult.rejected(
                        "Cannot refund: no Razorpay payment ID associated with this record."));

        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(1000L, "broken", null), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no Razorpay payment ID");
    }

    // ═══════════════════════════ ALREADY_RESERVED → idempotency-key retry routing ═══════════════

    @Test
    void alreadyReserved_pending_returnsPendingResponse_neverCallsGatewayOrFinalize() {
        Refund existing = pendingRefund(704L, 100000L, "retry");
        when(refundSettlementService.reserve(eq(PAYMENT_ID), any(), eq(SCHOOL_ID)))
                .thenReturn(RefundSettlementService.ReservationResult.alreadyReserved(existing));

        Map<String, Object> result = service.processRefund(
                PAYMENT_ID, refundRequest(100000L, "retry", "retry-key"), "admin", "ADMIN", "127.0.0.1");

        assertThat(result.get("status")).isEqualTo("pending");
        assertThat(result.get("refundId")).isEqualTo(704L);
        verify(razorpayService, never()).createRefund(any(), anyLong(), any());
        verify(refundSettlementService, never()).finalizeSuccessfulRefund(any(), any(), any(), any(), any(), any());
    }

    @Test
    void alreadyReserved_success_throwsIllegalStateException() {
        Refund existing = pendingRefund(705L, 100000L, "done");
        existing.setStatus(RefundSettlementService.STATUS_SUCCESS);
        when(refundSettlementService.reserve(eq(PAYMENT_ID), any(), eq(SCHOOL_ID)))
                .thenReturn(RefundSettlementService.ReservationResult.alreadyReserved(existing));

        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(100000L, "retry", "retry-key-1"), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idempotency");
    }

    @Test
    void alreadyReserved_failed_throwsIllegalStateExceptionAdvisingRetryWithNewRequest() {
        Refund existing = pendingRefund(706L, 100000L, "failed attempt");
        existing.setStatus(RefundSettlementService.STATUS_FAILED);
        when(refundSettlementService.reserve(eq(PAYMENT_ID), any(), eq(SCHOOL_ID)))
                .thenReturn(RefundSettlementService.ReservationResult.alreadyReserved(existing));

        assertThatThrownBy(() -> service.processRefund(
                PAYMENT_ID, refundRequest(100000L, "retry", "dead-key"), "admin", "ADMIN", "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed");
    }

    // ─── getPaymentLineItemBreakdown — Phase 4 receipt/payment-detail line-item alignment ──
    // (unrelated to refund integrity; unchanged from before Phase B)

    private StudentFees paidRow(int month, BigDecimal amountPaid) {
        StudentFees fee = new StudentFees();
        fee.setId(1000L + month);
        fee.setStudentId("S1");
        fee.setSchoolId(SCHOOL_ID);
        fee.setYear("2025-2026");
        fee.setMonth(month);
        fee.setPaid(true);
        fee.setAmountPaid(amountPaid);
        return fee;
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
        Payment payment = razorpayPayment();
        when(paymentRepository.findByPaymentIdAndSchoolId("pay_razorpay123", SCHOOL_ID)).thenReturn(Optional.of(payment));
        StudentFees m1 = paidRow(1, BigDecimal.valueOf(2000));
        StudentFees m2 = paidRow(2, BigDecimal.valueOf(2000));
        when(studentFeesRepository.findById(m1.getId())).thenReturn(Optional.of(m1));
        when(studentFeesRepository.findById(m2.getId())).thenReturn(Optional.of(m2));
        when(feeCalculationService.resolveSchoolFeeDue(m1, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        when(feeCalculationService.resolveSchoolFeeDue(m2, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
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
            assertThat(li.getNetAmount()).isEqualByComparingTo("3000");
        });
        assertThat(dto.getLineItems()).anySatisfy(li -> {
            assertThat(li.getFeeHeadName()).isEqualTo("Bus Fee");
            assertThat(li.getNetAmount()).isEqualByComparingTo("1000");
        });

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
        StudentFees m1 = paidRow(1, BigDecimal.valueOf(2000));
        StudentFees m2 = paidRow(2, BigDecimal.valueOf(2000));
        when(studentFeesRepository.findById(m1.getId())).thenReturn(Optional.of(m1));
        when(studentFeesRepository.findById(m2.getId())).thenReturn(Optional.of(m2));
        when(feeCalculationService.resolveSchoolFeeDue(m1, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        when(feeCalculationService.resolveSchoolFeeDue(m2, SCHOOL_ID, "2025-2026")).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        PaymentStudentFeesAllocation a1 = allocation(23L, PAYMENT_ID, m1.getId(), 1, 200000L);
        PaymentStudentFeesAllocation a2 = allocation(24L, PAYMENT_ID, m2.getId(), 2, 200000L);
        when(paymentAllocationRepository.findByPaymentIdOrderByMonthAsc(PAYMENT_ID)).thenReturn(List.of(a1, a2));

        when(studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(m1.getId())).thenReturn(List.of(
                lineItem(m1.getId(), LineItemType.FEE_HEAD, "Tuition Fee", 200000L, 0L)
        ));
        when(studentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById(m2.getId())).thenReturn(List.of());

        PaymentLineItemBreakdownDto dto = service.getPaymentLineItemBreakdown("pay_razorpay123").orElseThrow();

        assertThat(dto.isLineItemBreakdownAvailable()).isFalse();
        assertThat(dto.getLineItems()).isEmpty();
        assertThat(dto.getTotalSchoolFeeDue()).isEqualByComparingTo("4000");
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
