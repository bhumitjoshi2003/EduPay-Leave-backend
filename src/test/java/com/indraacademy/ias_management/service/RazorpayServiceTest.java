package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.razorpay.Utils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Consumer-migration coverage: calculateOutstandingBalancePaise (the payment-amount ceiling
 * validated on POST /api/payments/create) used to estimate a flat per-month figure from the
 * legacy FeeStructure table, applied uniformly to every unpaid row regardless of its actual
 * class/month. It now sums each unpaid row's own resolved StudentFees snapshot — the same
 * figure checkout/reminders use — via FeeCalculationService.resolveSchoolFeeDue.
 */
@ExtendWith(MockitoExtension.class)
class RazorpayServiceTest {

    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private FeeCalculationService feeCalculationService;
    @Mock private SecurityUtil securityUtil;
    @Mock private PaymentSettlementService paymentSettlementService;
    @Mock private BusinessNotificationService businessNotifications;
    @Mock private StudentRepository studentRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private EmailService emailService;
    @Mock private com.indraacademy.ias_management.repository.RefundRepository refundRepository;
    @Mock private RefundSettlementService refundSettlementService;
    @Mock private com.indraacademy.ias_management.repository.AcademicSessionRepository academicSessionRepository;

    private RazorpayService service;

    private static final Long SCHOOL_ID = 1L;
    private static final String SESSION = "2025-2026";
    private static final String ORDER_ID = "order_ABC";
    private static final String PAYMENT_ID = "pay_XYZ";
    private static final String SIGNATURE = "sig_123";

    @BeforeEach
    void setUp() {
        service = new RazorpayService();
        ReflectionTestUtils.setField(service, "studentFeesRepository", studentFeesRepository);
        ReflectionTestUtils.setField(service, "feeCalculationService", feeCalculationService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "paymentSettlementService", paymentSettlementService);
        ReflectionTestUtils.setField(service, "businessNotifications", businessNotifications);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "refundRepository", refundRepository);
        ReflectionTestUtils.setField(service, "refundSettlementService", refundSettlementService);
        ReflectionTestUtils.setField(service, "academicSessionRepository", academicSessionRepository);
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
    }

    // ── createOrder: session must resolve to a real AcademicSession before anything else ───

    /** Closes the pre-existing gap where PaymentOrder.session was only @NotBlank-checked: an
     * unresolvable session must be rejected before any PaymentOrder row is persisted, and —
     * just as importantly — before the real Razorpay API is ever called (no orphaned remote
     * order for a session that doesn't exist). */
    @Test
    void createOrder_unresolvableSession_rejectsBeforePersistingOrCallingRazorpay() {
        when(academicSessionRepository.findBySchoolIdAndLabel(SCHOOL_ID, "2099-2100")).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createOrder(
                        100000, "S1", "Student One", "6A", "2099-2100", "000000000000",
                        0, 0, 0, 0, 0, 0, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AcademicSession not found");
    }

    // ── reconcileRefund: pull-based reconciliation (Phase C) ────────────────────────────────

    private com.indraacademy.ias_management.entity.Refund pendingRefund(Long id, String providerRefundId) {
        com.indraacademy.ias_management.entity.Refund refund = new com.indraacademy.ias_management.entity.Refund();
        refund.setId(id);
        refund.setPaymentId(999L);
        refund.setStatus(RefundSettlementService.STATUS_PENDING);
        refund.setProviderRefundId(providerRefundId);
        refund.setAmountPaise(100000L);
        return refund;
    }

    @Test
    void reconcileRefund_notFound_doesNothing() {
        when(refundRepository.findById(1L)).thenReturn(Optional.empty());

        service.reconcileRefund(1L);

        verifyNoInteractions(refundSettlementService);
    }

    @Test
    void reconcileRefund_alreadyTerminal_doesNothing() {
        var refund = pendingRefund(2L, "rfnd_x");
        refund.setStatus(RefundSettlementService.STATUS_SUCCESS);
        when(refundRepository.findById(2L)).thenReturn(Optional.of(refund));

        service.reconcileRefund(2L);

        verifyNoInteractions(refundSettlementService);
    }

    /** Task 12/17: PENDING with no known provider refund id must NEVER trigger a provider
     * refund creation, and has nothing safe to look up either — confirmed by verifying
     * refundSettlementService (the only path to any local mutation) is never touched. */
    @Test
    void reconcileRefund_pendingWithNoProviderRefundId_neverCallsProviderOrMutatesState() {
        var refund = pendingRefund(3L, null);
        when(refundRepository.findById(3L)).thenReturn(Optional.of(refund));

        service.reconcileRefund(3L);

        verifyNoInteractions(refundSettlementService);
    }

    /** Provider lookup exception (here: Razorpay keys not configured, surfacing the same way a
     * real network failure would — both are RuntimeExceptions from fetchRefund) — local state
     * must stay completely untouched, never guessed into any resolution. */
    @Test
    void reconcileRefund_providerLookupFails_leavesStateUnchanged() {
        var refund = pendingRefund(4L, "rfnd_lookup_fails");
        when(refundRepository.findById(4L)).thenReturn(Optional.of(refund));
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.empty()); // falls through to blank global keys

        service.reconcileRefund(4L);

        verifyNoInteractions(refundSettlementService);
    }

    private Map<String, String> paymentData() {
        Map<String, String> data = new HashMap<>();
        data.put("razorpay_order_id", ORDER_ID);
        data.put("razorpay_payment_id", PAYMENT_ID);
        data.put("razorpay_signature", SIGNATURE);
        return data;
    }

    private Payment settledPayment() {
        Payment payment = new Payment();
        payment.setId(500L);
        payment.setStudentId("S1");
        payment.setStudentName("Test Student");
        payment.setSession(SESSION);
        payment.setMonth("100000000000");
        payment.setAmount(250000);
        payment.setPaymentId(PAYMENT_ID);
        payment.setOrderId(ORDER_ID);
        return payment;
    }

    // ── verifyPayment: delegation to PaymentSettlementService ───────────────────────────────

    @Test
    void verifyPayment_missingFields_rejectsBeforeCallingSettlementService() {
        Map<String, Object> result = service.verifyPayment(Map.of("razorpay_order_id", ORDER_ID), null);

        assertThat(result.get("success")).isEqualTo(false);
        verifyNoInteractions(paymentSettlementService);
    }

    @Test
    void verifyPayment_invalidSignature_rejectsBeforeCallingSettlementService() {
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifySignature(anyString(), anyString(), any())).thenReturn(false);

            Map<String, Object> result = service.verifyPayment(paymentData(), null);

            assertThat(result.get("success")).isEqualTo(false);
            assertThat((String) result.get("message")).contains("Invalid Signature");
            verifyNoInteractions(paymentSettlementService);
        }
    }

    @Test
    void verifyPayment_settledOutcome_sendsNotificationAndEmail_reportsSuccess() {
        Payment payment = settledPayment();
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID))
                .thenReturn(Optional.of(student("S1", "Test Student", "parent@example.com")));
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Test School")));

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifySignature(anyString(), anyString(), any())).thenReturn(true);
            when(paymentSettlementService.settle(ORDER_ID, PAYMENT_ID, SIGNATURE, SCHOOL_ID, PaymentSettlementService.SettlementSource.CLIENT_VERIFY))
                    .thenReturn(new PaymentSettlementService.SettlementResult(
                            PaymentSettlementService.Outcome.SETTLED, "Payment Verified Successfully", payment));

            Map<String, Object> result = service.verifyPayment(paymentData(), null);

            assertThat(result.get("success")).isEqualTo(true);
            assertThat(result.get("message")).isEqualTo("Payment Verified Successfully");
            verify(businessNotifications).studentAndParents(eq(SCHOOL_ID), eq("S1"), any(), any(), any(),
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
            verify(emailService).sendHtmlEmail(eq("parent@example.com"), anyString(), anyString());
        }
    }

    @Test
    void verifyPayment_notificationThrows_settlementStillReportedSuccessful() {
        // The exact false-negative bug Phase B fixes: financial settlement already committed
        // (settle() has already returned SETTLED by this point) — a failure sending the
        // notification/email must never flip the response to failure.
        Payment payment = settledPayment();
        doThrow(new RuntimeException("push notification boom"))
                .when(businessNotifications).studentAndParents(any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any(), any(), any(), any());

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifySignature(anyString(), anyString(), any())).thenReturn(true);
            when(paymentSettlementService.settle(ORDER_ID, PAYMENT_ID, SIGNATURE, SCHOOL_ID, PaymentSettlementService.SettlementSource.CLIENT_VERIFY))
                    .thenReturn(new PaymentSettlementService.SettlementResult(
                            PaymentSettlementService.Outcome.SETTLED, "Payment Verified Successfully", payment));

            Map<String, Object> result = service.verifyPayment(paymentData(), null);

            assertThat(result.get("success")).isEqualTo(true);
            assertThat(result.get("message")).isEqualTo("Payment Verified Successfully");
        }
    }

    @Test
    void verifyPayment_alreadySettled_doesNotResendNotification() {
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifySignature(anyString(), anyString(), any())).thenReturn(true);
            when(paymentSettlementService.settle(ORDER_ID, PAYMENT_ID, SIGNATURE, SCHOOL_ID, PaymentSettlementService.SettlementSource.CLIENT_VERIFY))
                    .thenReturn(new PaymentSettlementService.SettlementResult(
                            PaymentSettlementService.Outcome.ALREADY_SETTLED, "Payment already verified.", null));

            Map<String, Object> result = service.verifyPayment(paymentData(), null);

            assertThat(result.get("success")).isEqualTo(true);
            assertThat(result.get("message")).isEqualTo("Payment already verified.");
            verifyNoInteractions(businessNotifications, emailService);
        }
    }

    @Test
    void verifyPayment_rejected_reportsFailureWithMessage() {
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifySignature(anyString(), anyString(), any())).thenReturn(true);
            when(paymentSettlementService.settle(ORDER_ID, PAYMENT_ID, SIGNATURE, SCHOOL_ID, PaymentSettlementService.SettlementSource.CLIENT_VERIFY))
                    .thenReturn(new PaymentSettlementService.SettlementResult(
                            PaymentSettlementService.Outcome.REJECTED, "Payment Verification Failed: Order already used.", null));

            Map<String, Object> result = service.verifyPayment(paymentData(), null);

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(result.get("message")).isEqualTo("Payment Verification Failed: Order already used.");
            verifyNoInteractions(businessNotifications, emailService);
        }
    }

    private Student student(String studentId, String name, String email) {
        Student s = new Student();
        s.setStudentId(studentId);
        s.setName(name);
        s.setEmail(email);
        return s;
    }

    private School school(String name) {
        School s = new School();
        s.setName(name);
        return s;
    }

    private StudentFees unpaidRow(int month, String className) {
        StudentFees fee = new StudentFees();
        fee.setStudentId("S1");
        fee.setSchoolId(SCHOOL_ID);
        fee.setYear(SESSION);
        fee.setMonth(month);
        fee.setClassName(className);
        fee.setPaid(false);
        return fee;
    }

    @Test
    void ceilingUsesEachRowsOwnResolvedSnapshotAmount_notAFlatEstimateFromTheFirstRowsClass() {
        // Two rows, DIFFERENT classes — the old implementation used only the first row's
        // class for a flat legacy-FeeStructure estimate applied to every row; the new one
        // must resolve each row independently.
        StudentFees row1 = unpaidRow(1, "5A");
        StudentFees row2 = unpaidRow(2, "6B");
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndPaidFalse("S1", SCHOOL_ID)).thenReturn(List.of(row1, row2));
        when(feeCalculationService.resolveSchoolFeeDue(row1, SCHOOL_ID, SESSION)).thenReturn(Optional.of(BigDecimal.valueOf(2000)));
        when(feeCalculationService.resolveSchoolFeeDue(row2, SCHOOL_ID, SESSION)).thenReturn(Optional.of(BigDecimal.valueOf(3500)));

        long ceilingPaise = service.calculateOutstandingBalancePaise("S1", SESSION);

        // ceiling = ((2000+lateBuffer) + (3500+lateBuffer)) * 1.2, at minimum > the raw sum
        // in paise — confirms real per-row amounts were used, not a flat legacy estimate.
        assertThat(ceilingPaise).isGreaterThan(550_000L); // > (2000+3500)*100 paise
    }

    @Test
    void unresolvableRowStillContributesAFallbackEstimate_neverShrinksTheCeilingToZero() {
        StudentFees row = unpaidRow(1, "5A");
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndPaidFalse("S1", SCHOOL_ID)).thenReturn(List.of(row));
        when(feeCalculationService.resolveSchoolFeeDue(row, SCHOOL_ID, SESSION)).thenReturn(Optional.empty());

        long ceilingPaise = service.calculateOutstandingBalancePaise("S1", SESSION);

        // Must still be a real, generous ceiling — not 0 or near-0 just because one row's
        // exact amount is unknown (would wrongly reject a legitimate payment).
        assertThat(ceilingPaise).isGreaterThan(500_000L);
    }

    @Test
    void noUnpaidFeesReturnsZero() {
        when(studentFeesRepository.findByStudentIdAndSchoolIdAndPaidFalse("S1", SCHOOL_ID)).thenReturn(List.of());

        assertThat(service.calculateOutstandingBalancePaise("S1", SESSION)).isZero();
    }
}
