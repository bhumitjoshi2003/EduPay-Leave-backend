package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.PaymentOrder;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.PaymentOrderRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static com.indraacademy.ias_management.service.RazorpayService.WebhookProcessingResult.RetryDisposition.ACKNOWLEDGE;
import static com.indraacademy.ias_management.service.RazorpayService.WebhookProcessingResult.RetryDisposition.RETRY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Razorpay Payment-Integrity Hardening, Phase C — RazorpayService.processWebhookEvent /
 * recoverPaymentFromWebhook. Covers the webhook side of convergence onto
 * PaymentSettlementService; the settlement service's own atomicity/locking guarantees are
 * already proved (Phase B) by PaymentSettlementServiceTest and ...PostgresIT — this file only
 * needs to prove RazorpayService correctly decides WHEN to call settle() and how to translate
 * its outcome into a webhook response, using a mocked PaymentSettlementService throughout.
 */
@ExtendWith(MockitoExtension.class)
class RazorpayServiceWebhookTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentOrderRepository paymentOrderRepository;
    @Mock private PaymentSettlementService paymentSettlementService;
    @Mock private BusinessNotificationService businessNotifications;
    @Mock private StudentRepository studentRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private EmailService emailService;

    private RazorpayService service;

    private static final Long SCHOOL_ID = 1L;
    private static final String ORDER_ID = "order_WH1";
    private static final String PAYMENT_ID = "pay_WH1";
    private static final long AMOUNT_PAISE = 250000L;

    @BeforeEach
    void setUp() {
        service = new RazorpayService();
        ReflectionTestUtils.setField(service, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(service, "paymentOrderRepository", paymentOrderRepository);
        ReflectionTestUtils.setField(service, "paymentSettlementService", paymentSettlementService);
        ReflectionTestUtils.setField(service, "businessNotifications", businessNotifications);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "emailService", emailService);
    }

    private PaymentOrder freshOrder() {
        PaymentOrder order = new PaymentOrder();
        order.setOrderId(ORDER_ID);
        order.setSchoolId(SCHOOL_ID);
        order.setStudentId("S1");
        order.setClassName("6A");
        order.setSession("2025-2026");
        order.setMonth("100000000000");
        order.setAmount((int) AMOUNT_PAISE);
        return order;
    }

    private Payment settledPayment() {
        Payment payment = new Payment();
        payment.setId(700L);
        payment.setStudentId("S1");
        payment.setStudentName("Test Student");
        payment.setSession("2025-2026");
        payment.setMonth("100000000000");
        payment.setAmount((int) AMOUNT_PAISE);
        payment.setPaymentId(PAYMENT_ID);
        payment.setOrderId(ORDER_ID);
        return payment;
    }

    private String webhookPayload(String eventType, String paymentId, String orderId, Long amountPaise, String currency) {
        JSONObject entity = new JSONObject();
        if (paymentId != null) entity.put("id", paymentId);
        if (orderId != null) entity.put("order_id", orderId);
        if (amountPaise != null) entity.put("amount", amountPaise);
        if (currency != null) entity.put("currency", currency);
        entity.put("status", "captured");

        JSONObject payment = new JSONObject().put("entity", entity);
        JSONObject paymentWrapper = new JSONObject().put("payment", payment);
        return new JSONObject().put("event", eventType).put("payload", paymentWrapper).toString();
    }

    // ── 1. captured webhook, unrecorded payment -> settles ──────────────────────────────────

    @Test
    void capturedWebhook_unrecordedPayment_callsSettlementService_settlementSucceeds() {
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false);
        when(paymentOrderRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(freshOrder()));
        when(paymentSettlementService.settle(ORDER_ID, PAYMENT_ID, null, SCHOOL_ID,
                PaymentSettlementService.SettlementSource.RAZORPAY_WEBHOOK))
                .thenReturn(new PaymentSettlementService.SettlementResult(
                        PaymentSettlementService.Outcome.SETTLED, "Payment Verified Successfully", settledPayment()));
        when(studentRepository.findByStudentIdAndSchoolId(any(), any())).thenReturn(Optional.empty());

        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent(
                webhookPayload("payment.captured", PAYMENT_ID, ORDER_ID, AMOUNT_PAISE, "INR"));

        assertThat(result.retryDisposition()).isEqualTo(ACKNOWLEDGE);
        verify(paymentSettlementService).settle(ORDER_ID, PAYMENT_ID, null, SCHOOL_ID,
                PaymentSettlementService.SettlementSource.RAZORPAY_WEBHOOK);
    }

    // ── 2. captured webhook, already settled -> idempotent no-op ────────────────────────────

    @Test
    void capturedWebhook_alreadySettled_idempotentNoOp_neverCallsSettlementService() {
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(true);

        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent(
                webhookPayload("payment.captured", PAYMENT_ID, ORDER_ID, AMOUNT_PAISE, "INR"));

        assertThat(result.retryDisposition()).isEqualTo(ACKNOWLEDGE);
        verifyNoInteractions(paymentSettlementService, paymentOrderRepository);
    }

    // ── 3. webhook settles first -> a later client-verify call reports ALREADY_SETTLED ──────

    @Test
    void webhookSettlesFirst_laterClientVerifyReportsAlreadySettled() {
        // Simulates: this same PaymentSettlementService (real one, not this mock, in
        // production) already settled via the webhook path moments earlier — a subsequent
        // client verify for the identical paymentId reaches settle() again and gets
        // ALREADY_SETTLED back, exactly as Phase B's own idempotency guarantees.
        when(paymentSettlementService.settle(eq(ORDER_ID), eq(PAYMENT_ID), anyString(), eq(SCHOOL_ID),
                eq(PaymentSettlementService.SettlementSource.CLIENT_VERIFY)))
                .thenReturn(new PaymentSettlementService.SettlementResult(
                        PaymentSettlementService.Outcome.ALREADY_SETTLED, "Payment already verified.", null));

        try (var utils = mockStatic(com.razorpay.Utils.class)) {
            utils.when(() -> com.razorpay.Utils.verifySignature(anyString(), anyString(), any())).thenReturn(true);
            ReflectionTestUtils.setField(service, "securityUtil", securityUtilReturning(SCHOOL_ID));

            var paymentData = java.util.Map.of(
                    "razorpay_order_id", ORDER_ID, "razorpay_payment_id", PAYMENT_ID, "razorpay_signature", "sig");
            var result = service.verifyPayment(paymentData, null);

            assertThat(result.get("success")).isEqualTo(true);
            assertThat(result.get("message")).isEqualTo("Payment already verified.");
            verifyNoInteractions(businessNotifications, emailService); // never re-notifies on a duplicate
        }
    }

    // ── 4. client verify settles first -> webhook is a no-op ────────────────────────────────

    @Test
    void clientVerifySettlesFirst_webhookIsNoOp() {
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(true);

        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent(
                webhookPayload("payment.captured", PAYMENT_ID, ORDER_ID, AMOUNT_PAISE, "INR"));

        assertThat(result.retryDisposition()).isEqualTo(ACKNOWLEDGE);
        assertThat(result.detail()).isEqualTo("already settled");
        verifyNoInteractions(paymentSettlementService);
    }

    // ── 5. duplicate captured webhook deliveries -> settle() called at most once ────────────

    @Test
    void duplicateCapturedWebhookDeliveries_settleCalledExactlyOnce() {
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false, true);
        when(paymentOrderRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(freshOrder()));
        when(paymentSettlementService.settle(ORDER_ID, PAYMENT_ID, null, SCHOOL_ID,
                PaymentSettlementService.SettlementSource.RAZORPAY_WEBHOOK))
                .thenReturn(new PaymentSettlementService.SettlementResult(
                        PaymentSettlementService.Outcome.SETTLED, "Payment Verified Successfully", settledPayment()));
        when(studentRepository.findByStudentIdAndSchoolId(any(), any())).thenReturn(Optional.empty());

        String payload = webhookPayload("payment.captured", PAYMENT_ID, ORDER_ID, AMOUNT_PAISE, "INR");
        service.processWebhookEvent(payload); // first delivery: settles
        service.processWebhookEvent(payload); // redelivery: existsByPaymentId now true

        verify(paymentSettlementService, times(1)).settle(any(), any(), any(), any(), any());
    }

    // ── 7. wrong webhook amount -> rejected, never settles ──────────────────────────────────

    @Test
    void wrongWebhookAmount_rejectedWithoutSettling() {
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false);
        when(paymentOrderRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(freshOrder()));

        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent(
                webhookPayload("payment.captured", PAYMENT_ID, ORDER_ID, AMOUNT_PAISE + 1, "INR"));

        assertThat(result.retryDisposition()).isEqualTo(ACKNOWLEDGE);
        assertThat(result.detail()).isEqualTo("amount mismatch");
        verifyNoInteractions(paymentSettlementService);
    }

    @Test
    void wrongWebhookCurrency_rejectedWithoutSettling() {
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false);
        when(paymentOrderRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(freshOrder()));

        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent(
                webhookPayload("payment.captured", PAYMENT_ID, ORDER_ID, AMOUNT_PAISE, "USD"));

        assertThat(result.retryDisposition()).isEqualTo(ACKNOWLEDGE);
        assertThat(result.detail()).isEqualTo("currency mismatch");
        verifyNoInteractions(paymentSettlementService);
    }

    // ── 8. unknown order -> no settlement, never fabricates an orphan Payment ───────────────

    @Test
    void unknownOrder_noSettlement() {
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false);
        when(paymentOrderRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent(
                webhookPayload("payment.captured", PAYMENT_ID, ORDER_ID, AMOUNT_PAISE, "INR"));

        assertThat(result.retryDisposition()).isEqualTo(ACKNOWLEDGE);
        assertThat(result.detail()).isEqualTo("unknown order");
        verifyNoInteractions(paymentSettlementService);
    }

    // ── 9. malformed webhook shapes -> safe, acknowledged, never throw ──────────────────────

    @Test
    void malformedJson_acknowledgedSafely() {
        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent("{not valid json");

        assertThat(result.retryDisposition()).isEqualTo(ACKNOWLEDGE);
        verifyNoInteractions(paymentSettlementService);
    }

    @Test
    void missingPayloadKey_acknowledgedSafely() {
        String payload = new JSONObject().put("event", "payment.captured").toString();

        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent(payload);

        assertThat(result.retryDisposition()).isEqualTo(ACKNOWLEDGE);
        assertThat(result.detail()).isEqualTo("no payload");
    }

    @Test
    void missingPaymentObject_acknowledgedSafely() {
        JSONObject payloadObj = new JSONObject(); // no "payment" key
        String payload = new JSONObject().put("event", "payment.captured").put("payload", payloadObj).toString();

        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent(payload);

        assertThat(result.retryDisposition()).isEqualTo(ACKNOWLEDGE);
        assertThat(result.detail()).isEqualTo("no payment object");
    }

    @Test
    void missingEntity_acknowledgedSafely() {
        JSONObject payment = new JSONObject(); // no "entity" key
        JSONObject payloadObj = new JSONObject().put("payment", payment);
        String payload = new JSONObject().put("event", "payment.captured").put("payload", payloadObj).toString();

        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent(payload);

        assertThat(result.retryDisposition()).isEqualTo(ACKNOWLEDGE);
        assertThat(result.detail()).isEqualTo("no entity");
    }

    @Test
    void missingIdOrOrderId_acknowledgedSafely() {
        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent(
                webhookPayload("payment.captured", null, ORDER_ID, AMOUNT_PAISE, "INR"));

        assertThat(result.retryDisposition()).isEqualTo(ACKNOWLEDGE);
        assertThat(result.detail()).isEqualTo("missing id/order_id");
        verifyNoInteractions(paymentSettlementService);
    }

    // ── 10. notification failure after webhook settlement -> settlement remains successful ─

    @Test
    void notificationFailureAfterWebhookSettlement_settlementRemainsSuccessful() {
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false);
        when(paymentOrderRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(freshOrder()));
        when(paymentSettlementService.settle(ORDER_ID, PAYMENT_ID, null, SCHOOL_ID,
                PaymentSettlementService.SettlementSource.RAZORPAY_WEBHOOK))
                .thenReturn(new PaymentSettlementService.SettlementResult(
                        PaymentSettlementService.Outcome.SETTLED, "Payment Verified Successfully", settledPayment()));
        doThrow(new RuntimeException("push boom"))
                .when(businessNotifications).studentAndParents(any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any(), any(), any(), any());

        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent(
                webhookPayload("payment.captured", PAYMENT_ID, ORDER_ID, AMOUNT_PAISE, "INR"));

        assertThat(result.retryDisposition()).isEqualTo(ACKNOWLEDGE);
        assertThat(result.detail()).isEqualTo("Payment Verified Successfully");
    }

    // ── 11. payment.authorized is observational only, never settles ────────────────────────

    @Test
    void paymentAuthorized_isObservationalOnly_neverSettles() {
        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent(
                webhookPayload("payment.authorized", PAYMENT_ID, ORDER_ID, AMOUNT_PAISE, "INR"));

        assertThat(result.retryDisposition()).isEqualTo(ACKNOWLEDGE);
        assertThat(result.detail()).isEqualTo("authorized (observational)");
        verifyNoInteractions(paymentSettlementService, paymentOrderRepository, paymentRepository);
    }

    @Test
    void paymentFailed_isObservationalOnly() {
        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent(
                webhookPayload("payment.failed", PAYMENT_ID, ORDER_ID, AMOUNT_PAISE, "INR"));

        assertThat(result.retryDisposition()).isEqualTo(ACKNOWLEDGE);
        verifyNoInteractions(paymentSettlementService);
    }

    // ── Transient internal failure -> RETRY, distinct from a permanent rejection ───────────

    @Test
    void transientErrorDuringRecovery_requestsRetry() {
        when(paymentRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false);
        when(paymentOrderRepository.findByOrderId(ORDER_ID)).thenThrow(new RuntimeException("DB blip"));

        RazorpayService.WebhookProcessingResult result = service.processWebhookEvent(
                webhookPayload("payment.captured", PAYMENT_ID, ORDER_ID, AMOUNT_PAISE, "INR"));

        assertThat(result.retryDisposition()).isEqualTo(RETRY);
    }

    private com.indraacademy.ias_management.util.SecurityUtil securityUtilReturning(Long schoolId) {
        com.indraacademy.ias_management.util.SecurityUtil securityUtil =
                mock(com.indraacademy.ias_management.util.SecurityUtil.class);
        lenient().when(securityUtil.getSchoolId()).thenReturn(schoolId);
        return securityUtil;
    }
}
