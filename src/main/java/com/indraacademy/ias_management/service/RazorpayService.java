package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.PaymentOrder;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.PaymentOrderRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import com.indraacademy.ias_management.notification.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class RazorpayService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayService.class);

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentOrderRepository paymentOrderRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private EmailService emailService;
    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private PaymentSettlementService paymentSettlementService;
    @Autowired private BusinessNotificationService businessNotifications;
    @Autowired private FeeCalculationService feeCalculationService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private com.indraacademy.ias_management.repository.RefundRepository refundRepository;
    @Autowired private RefundSettlementService refundSettlementService;
    @Autowired private com.indraacademy.ias_management.repository.AcademicSessionRepository academicSessionRepository;

    /** Global fallback keys from application.properties — used when a school has no own keys configured. */
    @Value("${razorpay.key.id:}")
    private String globalKeyId;

    @Value("${razorpay.key.secret:}")
    private String globalKeySecret;

    @Value("${razorpay.webhook.secret:}")
    private String webhookSecret;

    /**
     * Returns a RazorpayClient using the current school's own keys if configured,
     * falling back to the global application.properties keys.
     */
    private RazorpayClient getRazorpayClient() throws RazorpayException {
        String kid = resolveKeyId();
        String ksecret = resolveKeySecret();
        if (kid == null || kid.isBlank() || ksecret == null || ksecret.isBlank()) {
            throw new IllegalStateException("Razorpay keys are not configured for this school.");
        }
        return new RazorpayClient(kid, ksecret);
    }

    private String resolveKeyId() {
        Long schoolId = securityUtil.getSchoolId();
        if (schoolId != null) {
            School school = schoolRepository.findById(schoolId).orElse(null);
            if (school != null && school.getRazorpayKeyId() != null && !school.getRazorpayKeyId().isBlank()) {
                return school.getRazorpayKeyId();
            }
        }
        return globalKeyId;
    }

    private String resolveKeySecret() {
        Long schoolId = securityUtil.getSchoolId();
        if (schoolId != null) {
            School school = schoolRepository.findById(schoolId).orElse(null);
            if (school != null && school.getRazorpayKeySecret() != null && !school.getRazorpayKeySecret().isBlank()) {
                return school.getRazorpayKeySecret();
            }
        }
        return globalKeySecret;
    }

    /**
     * Creates a Razorpay order using the platform-global keys (for subscription plan upgrades).
     * Never uses school-specific keys — subscription revenue goes to the platform, not the school.
     */
    public Map<String, Object> createSubscriptionOrder(long amountPaise, Long planId, String planName, Long schoolId) {
        try {
            JSONObject options = new JSONObject();
            options.put("amount", amountPaise);
            options.put("currency", "INR");
            options.put("receipt", "sub_" + schoolId + "_" + System.currentTimeMillis());
            options.put("payment_capture", 1);

            Order order = getGlobalRazorpayClient().Orders.create(options);

            Map<String, Object> response = new HashMap<>();
            response.put("razorpayKey", globalKeyId);
            response.put("orderId", order.get("id"));
            response.put("amount", order.get("amount"));
            response.put("planId", planId);
            response.put("planName", planName);
            response.put("schoolId", schoolId);
            log.info("Subscription order created for school={} plan={} orderId={}", schoolId, planId, order.get("id"));
            return response;
        } catch (RazorpayException e) {
            log.error("Failed to create subscription order for school={} plan={}", schoolId, planId, e);
            throw new RuntimeException("Failed to create subscription payment order: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies the Razorpay signature for a subscription payment using global keys.
     * Returns true if signature is valid.
     */
    public boolean verifySubscriptionSignature(String orderId, String paymentId, String signature) throws RazorpayException {
        String payload = orderId + "|" + paymentId;
        return Utils.verifySignature(payload, signature, globalKeySecret);
    }

    private RazorpayClient getGlobalRazorpayClient() throws RazorpayException {
        if (globalKeyId == null || globalKeyId.isBlank() || globalKeySecret == null || globalKeySecret.isBlank()) {
            throw new IllegalStateException("Platform Razorpay keys are not configured. Contact support to upgrade your plan.");
        }
        return new RazorpayClient(globalKeyId, globalKeySecret);
    }

    /**
     * Calculates the total outstanding balance (in paise) for a student in a given session.
     * This is a ceiling-based validation: the actual payment can be less (partial payment)
     * but never more than the total outstanding.
     *
     * Sums each unpaid row's own stored StudentFees snapshot (baseAmountDue + busFeeDue −
     * discountAmount) via FeeCalculationService.resolveSchoolFeeDue — the same stable figure
     * checkout and reminders use — instead of a flat legacy-FeeStructure-based estimate
     * applied uniformly to every row regardless of its actual class/month. A row with no
     * trustworthy snapshot AND no dynamic rule to fall back on falls back to a generous flat
     * estimate (this is a ceiling, not an exact figure — never rejects a legitimate payment
     * just because one row's exact amount is unknown).
     */
    public long calculateOutstandingBalancePaise(String studentId, String session) {
        Long schoolId = securityUtil.getSchoolId();
        List<StudentFees> unpaidFees =
                studentFeesRepository.findByStudentIdAndSchoolIdAndPaidFalse(studentId, schoolId);

        if (unpaidFees.isEmpty()) {
            return 0;
        }

        // Filter to the requested session only
        List<StudentFees> sessionUnpaid = unpaidFees.stream()
                .filter(f -> session.equals(f.getYear()))
                .toList();

        if (sessionUnpaid.isEmpty()) {
            return 0;
        }

        // Generous flat estimate used only for a row whose amount is genuinely unresolvable
        // (no trustworthy snapshot, no dynamic rules either) — matches the old estimate's
        // order of magnitude so an unresolvable row still contributes a real ceiling instead
        // of silently shrinking the total (which could wrongly reject a legitimate payment).
        long fallbackPerMonthEstimatePaise = 5000_00L; // Rs 5000

        // Add late fee buffer per month
        long lateFeeCeilingPaise = 30L * 21L * 100L; // 30 days * Rs 21/day max late fee tier

        long totalCeiling = 0;
        for (StudentFees fee : sessionUnpaid) {
            Optional<BigDecimal> resolved = feeCalculationService.resolveSchoolFeeDue(fee, schoolId, session);
            long feePaise = resolved
                    .map(amount -> Math.round(amount.doubleValue() * 100.0))
                    .orElse(fallbackPerMonthEstimatePaise);
            totalCeiling += feePaise + lateFeeCeilingPaise;
        }

        // Add a 20% safety margin for additional charges, platform fees, etc.
        totalCeiling = (long) (totalCeiling * 1.2);

        // Floor: at minimum, allow at least the number of unpaid months * Rs 100 (in paise)
        long minimumCeiling = sessionUnpaid.size() * 100_00L;
        return Math.max(totalCeiling, minimumCeiling);
    }

    public Map<String, Object> createOrder(int amount, String studentId, String studentName, String className,
            String session, String month, Integer busFee, int tuitionFee, int annualCharges, int labCharges,
            int ecaProject, int examinationFee, int additionalCharges, int lateFees,
            OnlinePaymentPricingCalculator.Pricing pricing, Long paymentPricingConfigId) {
        if (amount <= 0 || studentId == null || studentId.trim().isEmpty()) {
            log.warn("Attempted to create order with invalid amount or missing student ID. Amount: {}", amount);
            throw new IllegalArgumentException("Invalid amount or missing student ID for order creation.");
        }

        // Amount is expected in paisa by Razorpay, but passed as an int representing paisa here.
        log.info("Creating Razorpay order for student ID: {} with amount: {} paisa", studentId, amount);

        // Resolved BEFORE ever calling out to Razorpay — closes the pre-existing gap where
        // PaymentOrder.session was only @NotBlank-checked, never validated against a real
        // AcademicSession, and avoids creating a real (if harmless/unused) remote order for a
        // session that doesn't exist. Both the stored label and the new authoritative id
        // persisted onto PaymentOrder below come from this ONE resolved row, never
        // independently trusted.
        Long schoolId = securityUtil.getSchoolId();
        AcademicSession academicSession = academicSessionRepository.findBySchoolIdAndLabel(schoolId, session)
                .orElseThrow(() -> new IllegalArgumentException(
                        "AcademicSession not found for schoolId=" + schoolId + ", session='" + session + "'"));

        try {
            JSONObject options = new JSONObject();
            options.put("amount", amount);
            options.put("currency", "INR");
            // Use a unique receipt ID in a real system (e.g., a hash or timestamp + ID)
            options.put("receipt", "txn_" + System.currentTimeMillis());
            options.put("payment_capture", 1);

            Order order = getRazorpayClient().Orders.create(options);
            String orderId = (String) order.get("id");

            // Persist the order server-side NOW, while every field here is still trusted
            // (the caller — PaymentController — has already verified the student identity
            // for this request). verifyPayment() below looks this row up by orderId instead
            // of trusting whatever a client claims the order was for at verify time — a
            // Razorpay signature only proves a payment was captured for this orderId, never
            // which student/amount/months it was actually created for.
            PaymentOrder paymentOrder = new PaymentOrder();
            paymentOrder.setOrderId(orderId);
            paymentOrder.setSchoolId(schoolId);
            paymentOrder.setStudentId(studentId);
            paymentOrder.setClassName(className);
            paymentOrder.setSession(academicSession.getLabel());
            paymentOrder.setAcademicSessionId(academicSession.getId());
            paymentOrder.setMonth(month);
            paymentOrder.setAmount(amount);
            paymentOrder.setBusFee(busFee != null ? busFee : 0);
            paymentOrder.setTuitionFee(tuitionFee);
            paymentOrder.setAnnualCharges(annualCharges);
            paymentOrder.setLabCharges(labCharges);
            paymentOrder.setEcaProject(ecaProject);
            paymentOrder.setExaminationFee(examinationFee);
            paymentOrder.setAdditionalCharges(additionalCharges);
            paymentOrder.setLateFees(lateFees);
            paymentOrder.setPlatformFee(0);
            paymentOrder.setSchoolLiabilityPrincipalPaise(pricing.schoolLiabilityPrincipalPaise());
            paymentOrder.setGatewayRateBps(pricing.gatewayRateBps());
            paymentOrder.setGatewayTaxRateBps(pricing.gatewayTaxRateBps());
            paymentOrder.setGatewayRecoveryFeePaise(pricing.gatewayRecoveryFeePaise());
            paymentOrder.setEdunexifyTransactionFeePaise(pricing.edunexifyTransactionFeePaise());
            paymentOrder.setPricingVersion(OnlinePaymentPricingCalculator.PRICING_VERSION);
            paymentOrder.setPaymentPricingConfigId(paymentPricingConfigId);
            paymentOrderRepository.save(paymentOrder);

            String schoolName = schoolRepository.findById(schoolId != null ? schoolId : -1L)
                    .map(School::getName).orElse("School");

            Map<String, Object> response = new HashMap<>();
            response.put("razorpayKey", resolveKeyId());
            response.put("orderId", order.get("id"));
            response.put("amount", order.get("amount")); // Amount in paisa
            response.put("schoolName", schoolName);
            response.put("studentId", studentId);
            response.put("studentName", studentName);
            response.put("className", className);
            response.put("session", session);
            response.put("month", month);
            response.put("busFee", busFee);
            response.put("tuitionFee", tuitionFee);
            response.put("annualCharges", annualCharges);
            response.put("labCharges", labCharges);
            response.put("ecaProject", ecaProject);
            response.put("examinationFee", examinationFee);
            response.put("paidManually", false);
            response.put("schoolFeePaise", Math.addExact(pricing.schoolLiabilityPrincipalPaise(),
                    pricing.otherCapturedNonConveniencePaise()));
            response.put("onlineConvenienceFeePaise", pricing.onlineConvenienceFeePaise());
            response.put("totalPayablePaise", pricing.totalPayablePaise());
            response.put("currency", EXPECTED_CURRENCY);

            log.info("Razorpay order created successfully. Order ID: {}", Optional.ofNullable(order.get("id")));
            return response;
        } catch (RazorpayException e) {
            log.error("Razorpay API error occurred during Order Creation for student ID: {}", studentId, e);
            throw new RuntimeException("Razorpay Order Creation Failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error during Order Creation for student ID: {}", studentId, e);
            throw new RuntimeException("Unexpected error during Order Creation", e);
        }
    }

    /**
     * Verifies a Razorpay payment signature for the CURRENT caller's school (own keys if
     * configured, else the global fallback) — the same check verifyPayment() below does,
     * exposed for other services (e.g. FeePaymentService) that record a Razorpay-mode
     * payment through a different path but must not skip this proof. A signature is the
     * ONLY evidence that a payment was genuinely captured by Razorpay for this orderId —
     * without it, a caller could claim any paymentId/orderId pair succeeded.
     */
    public boolean verifyPaymentSignatureForCurrentSchool(String orderId, String paymentId, String signature) {
        if (orderId == null || paymentId == null || signature == null) {
            return false;
        }
        try {
            return Utils.verifySignature(orderId + "|" + paymentId, signature, resolveKeySecret());
        } catch (RazorpayException e) {
            log.error("Signature verification error for orderId={}", orderId, e);
            return false;
        }
    }

    public Map<String, Object> verifyPayment(Map<String, String> paymentData, Map<String, Object> orderDetails) {
        Map<String, Object> response = new HashMap<>();
        String paymentId = paymentData != null ? paymentData.get("razorpay_payment_id") : null;
        String orderId = paymentData != null ? paymentData.get("razorpay_order_id") : null;
        String signature = paymentData != null ? paymentData.get("razorpay_signature") : null;

        log.info("Starting payment verification for Order ID: {}", orderId);

        if (paymentId == null || orderId == null || signature == null) {
            log.error("Payment verification data is incomplete. Order ID: {}", orderId);
            response.put("success", false);
            response.put("message", "Payment Verification Failed: Missing required fields.");
            return response;
        }

        try {
            // 1. Signature Verification — local HMAC, no network call, no DB transaction.
            //    Proves a real payment was captured for this orderId. It does NOT prove
            //    which student/amount/months that order was for — that's what
            //    PaymentSettlementService establishes below, from what WE persisted when
            //    the order was created, never from client-supplied data.
            String payload = orderId + "|" + paymentId;
            boolean isValid = Utils.verifySignature(payload, signature, resolveKeySecret());

            if (!isValid) {
                log.warn("Signature verification failed for Order ID: {}", orderId);
                response.put("success", false);
                response.put("message", "Payment Verification Failed: Invalid Signature");
                return response;
            }
            log.info("Signature verified successfully for Payment ID: {}", paymentId);

            // 2. Canonical, atomic settlement — one transaction owns Payment creation,
            //    PaymentOrder consumption, attendance charge-paid, and StudentFees
            //    allocation together. By the time settle() returns, that transaction has
            //    already committed or rolled back — there is nothing left open here for a
            //    later step (notifications) to affect.
            Long schoolId = securityUtil.getSchoolId();
            PaymentSettlementService.SettlementResult result =
                    paymentSettlementService.settle(orderId, paymentId, signature, schoolId,
                            PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

            response.put("success", result.outcome() != PaymentSettlementService.Outcome.REJECTED);
            response.put("message", result.message());

            // 3. Post-commit notification/email — best-effort only. Settlement has already
            //    committed at this point; nothing here can turn a successful settlement into
            //    a reported failure. Only sent for a genuinely fresh settlement, matching the
            //    pre-existing behavior of never re-notifying on an idempotent retry.
            if (result.outcome() == PaymentSettlementService.Outcome.SETTLED) {
                sendSettlementNotifications(result.payment(), schoolId);
            }

            return response;

        } catch (RazorpayException e) {
            log.error("Razorpay signature verification failed for Order ID: {}", orderId, e);
            response.put("success", false);
            response.put("message", "Payment Verification Failed due to signature error.");
            return response;
        } catch (DataAccessException e) {
            log.error("Data access error during payment persistence/updates for Order ID: {}", orderId, e);
            response.put("success", false);
            response.put("message", "Payment Verified but failed to save/update data.");
            return response;
        } catch (Exception e) {
            log.error("Unexpected error during Payment Verification for Order ID: {}", orderId, e);
            response.put("success", false);
            response.put("message", "Error Verifying Payment: " + e.getMessage());
            return response;
        }
    }

    /**
     * Sends the payment-success notification and confirmation email for a just-settled
     * payment. Called strictly after {@link PaymentSettlementService#settle} has returned
     * (i.e. strictly after that transaction committed) — never before, and never in a way
     * that can affect the settlement result already written into the caller's response map.
     * Any failure here is logged and swallowed; it must never be reported as a payment
     * failure, since the money and the fee-ledger state are already correctly recorded.
     */
    private void sendSettlementNotifications(Payment savedPayment, Long schoolId) {
        try {
            String studentId = savedPayment.getStudentId();
            String paymentId = savedPayment.getPaymentId();
            int amountInPaise = savedPayment.getAmount();
            double displayAmountRupees = amountInPaise / 100.0;
            String successNotificationMessage = String.format(
                    "Your fee payment of ₹%.2f has been successfully processed. Payment ID: %s", displayAmountRupees, paymentId);
            String relatedEntityId = (savedPayment.getId() != null) ? String.valueOf(savedPayment.getId()) : null;

            businessNotifications.studentAndParents(schoolId, studentId,
                    NotificationAudienceType.STUDENT_WITH_FEE_PARENTS,
                    NotificationEventCode.PAYMENT_SUCCESS, NotificationCategory.FEES_PAYMENTS,
                    "Payment Successful", successNotificationMessage, "Payment", relatedEntityId,
                    "/dashboard/payment-history", studentId, "payment-success:" + paymentId,
                    java.util.Set.of(ExternalDeliveryChannel.PUSH));
            log.info("Payment success notification initiated for student ID: {}", studentId);

            Optional<Student> studentOptional = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId);
            if (studentOptional.isPresent()) {
                Student student = studentOptional.get();
                String studentEmail = student.getEmail();

                if (studentEmail != null && !studentEmail.trim().isEmpty()) {
                    String subject = "Payment Confirmation – Fee Receipt";
                    String session = savedPayment.getSession();
                    String monthNames = convertMonthBitmask(savedPayment.getMonth(), schoolId);
                    String schoolName = schoolRepository.findById(schoolId)
                            .map(School::getName).orElse("School");
                    String htmlBody = buildPaymentConfirmationHtml(
                            student.getName(), paymentId, displayAmountRupees, session, monthNames, schoolName);

                    log.info("Initiating asynchronous HTML email send to {} for payment verification.", studentEmail);
                    emailService.sendHtmlEmail(studentEmail, subject, htmlBody);
                } else {
                    log.warn("Student email not found or is empty for student ID: {}. Skipping email notification.", studentId);
                }
            } else {
                log.warn("Student not found for ID: {}. Skipping email notification.", studentId);
            }
        } catch (Exception e) {
            log.error("Post-settlement notification/email failed for paymentId={} — settlement itself already " +
                    "committed successfully and is unaffected.", savedPayment.getPaymentId(), e);
        }
    }

    /** Converts a 12-char academic-month bitmask like "010000000000" to a real calendar
     * month name ("May"), or "April, May" for multi-month — using the paying student's own
     * school's configured academicYearStartMonth, not a hardcoded April-first array (bit
     * position i = academic month i+1, per StudentFees.month's convention). */
    private String convertMonthBitmask(String bitmask, Long schoolId) {
        if (bitmask == null || bitmask.isBlank()) return "—";
        int startMonth = schoolRepository.findById(schoolId)
                .map(School::getAcademicYearStartMonth).orElse(4);
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < Math.min(bitmask.length(), 12); i++) {
            if (bitmask.charAt(i) == '1') selected.add(feeCalculationService.getMonthName(i + 1, startMonth));
        }
        return selected.isEmpty() ? "—" : String.join(", ", selected);
    }

    /**
     * Verifies the Razorpay webhook signature against the configured webhook secret.
     */
    public boolean verifyWebhookSignature(String payload, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("Webhook secret is not configured. Cannot verify webhook signature.");
            return false;
        }
        try {
            // Razorpay webhook signatures use HMAC-SHA256 with the webhook secret.
            // Utils.verifySignature works for both payment signatures and webhook signatures.
            return Utils.verifySignature(payload, signature, webhookSecret);
        } catch (RazorpayException e) {
            log.error("Webhook signature verification failed.", e);
            return false;
        }
    }

    /** Every order this application creates is charged in this currency — hardcoded in
     * {@link #createOrder}'s Razorpay options, never per-school configurable — so a webhook
     * reporting anything else for one of our own orders is either a Razorpay-side inconsistency
     * or a mismatched/forged payload; either way, not something to settle against. */
    private static final String EXPECTED_CURRENCY = "INR";

    /** Tells {@code WebhookController} whether Razorpay should be told to retry this exact
     * webhook delivery. ACKNOWLEDGE covers both "handled successfully" and "permanently
     * inapplicable" (malformed payload, unknown order, amount mismatch, an order already
     * consumed by a different payment) — retrying an identical payload can never turn any of
     * those into a different outcome, so acknowledging (200) is what stops Razorpay's retry
     * schedule from hammering us pointlessly. RETRY is reserved for genuinely transient
     * failures (a DB blip, an unexpected exception) where trying again later might succeed. */
    public record WebhookProcessingResult(RetryDisposition retryDisposition, String detail) {
        public enum RetryDisposition { ACKNOWLEDGE, RETRY }

        static WebhookProcessingResult acknowledge(String detail) {
            return new WebhookProcessingResult(RetryDisposition.ACKNOWLEDGE, detail);
        }

        static WebhookProcessingResult retry(String detail) {
            return new WebhookProcessingResult(RetryDisposition.RETRY, detail);
        }
    }

    /**
     * Processes a Razorpay webhook event. Called from the webhook controller, strictly after
     * the webhook's own HMAC signature has already been verified there — this method trusts
     * that the payload genuinely came from Razorpay, but still never trusts any business field
     * inside it (schoolId/studentId/amount) over what the server-persisted PaymentOrder says;
     * see {@link #recoverPaymentFromWebhook}.
     */
    public WebhookProcessingResult processWebhookEvent(String payload) {
        JSONObject event;
        try {
            event = new JSONObject(payload);
        } catch (Exception e) {
            log.warn("Webhook payload is not valid JSON — cannot process. Acknowledging; retrying an " +
                    "identical malformed body can never succeed.", e);
            return WebhookProcessingResult.acknowledge("invalid JSON");
        }

        String eventType = event.optString("event", "");
        JSONObject paymentEntity = event.optJSONObject("payload");
        if (paymentEntity == null) {
            log.warn("Webhook event has no payload. Event type: {}", eventType);
            return WebhookProcessingResult.acknowledge("no payload");
        }

        if (eventType.startsWith("refund.")) {
            return handleRefundWebhookEvent(eventType, paymentEntity);
        }

        JSONObject paymentObj = paymentEntity.optJSONObject("payment");
        if (paymentObj == null) {
            log.info("Webhook event type '{}' has no payment object. Skipping.", eventType);
            return WebhookProcessingResult.acknowledge("no payment object");
        }

        JSONObject entity = paymentObj.optJSONObject("entity");
        if (entity == null) {
            log.warn("Webhook payment object has no entity. Event type: {}", eventType);
            return WebhookProcessingResult.acknowledge("no entity");
        }

        String razorpayPaymentId = entity.optString("id", null);
        String razorpayOrderId = entity.optString("order_id", null);
        String status = entity.optString("status", "");

        return switch (eventType) {
            case "payment.captured" -> handleCaptured(entity, razorpayPaymentId, razorpayOrderId);

            case "payment.authorized" -> {
                // Deliberately observational only, even though this app configures automatic
                // capture (payment_capture=1 in createOrder). Razorpay's own model treats
                // "authorized" as funds reserved, not yet guaranteed to the merchant —
                // "captured" is the only status Razorpay itself treats as final. The existing
                // client-verify path never sees a payment_id until Checkout.js's success
                // callback fires, which (for an auto-capture order) only happens post-capture
                // — so restricting settlement to payment.captured keeps the webhook path
                // consistent with what the client path has always implicitly assumed, rather
                // than broadening financial-success semantics on this phase's own authority.
                log.info("Webhook: payment.authorized for paymentId={} orderId={} — observational only, " +
                        "awaiting payment.captured before settling.", razorpayPaymentId, razorpayOrderId);
                yield WebhookProcessingResult.acknowledge("authorized (observational)");
            }

            case "payment.failed" -> {
                log.warn("Webhook: Payment failed. paymentId={} orderId={} status={}",
                        razorpayPaymentId, razorpayOrderId, status);
                yield WebhookProcessingResult.acknowledge("payment failed");
            }

            default -> {
                log.info("Webhook: Unhandled event type '{}'. Ignoring.", eventType);
                yield WebhookProcessingResult.acknowledge("unhandled event type");
            }
        };
    }

    private WebhookProcessingResult handleCaptured(JSONObject entity, String paymentId, String orderId) {
        if (paymentId == null || orderId == null) {
            log.warn("Webhook payment.captured missing id/order_id — cannot process.");
            return WebhookProcessingResult.acknowledge("missing id/order_id");
        }
        try {
            long amountPaise = entity.optLong("amount", -1);
            String currency = entity.optString("currency", null);
            return recoverPaymentFromWebhook(orderId, paymentId, amountPaise, currency);
        } catch (Exception e) {
            log.error("Unexpected error recovering payment from webhook for orderId={} — requesting retry.",
                    orderId, e);
            return WebhookProcessingResult.retry("internal error");
        }
    }

    /**
     * Webhook-triggered recovery of a client-verification callback that was lost — settles a
     * captured payment through the exact same {@link PaymentSettlementService} the client-verify
     * path uses, so a successful recovery produces identical canonical DB state either way.
     * <p>
     * schoolId/studentId always come from the server-persisted PaymentOrder (never from the
     * webhook payload, which has no concept of either) — a webhook only ever supplies the
     * Razorpay-side identifiers and amount, cross-checked against PaymentOrder below before
     * anything is settled.
     */
    private WebhookProcessingResult recoverPaymentFromWebhook(String orderId, String paymentId,
                                                               long amountPaise, String currency) {
        if (paymentRepository.existsByPaymentId(paymentId)) {
            log.info("Webhook: Payment {} already recorded. Skipping.", paymentId);
            return WebhookProcessingResult.acknowledge("already settled");
        }

        PaymentOrder paymentOrder = paymentOrderRepository.findByOrderId(orderId).orElse(null);
        if (paymentOrder == null) {
            log.error("Webhook payment.captured for unknown orderId — no matching PaymentOrder exists. " +
                    "Acknowledging without settling; this can never succeed on retry.");
            return WebhookProcessingResult.acknowledge("unknown order");
        }

        if (amountPaise != paymentOrder.getAmount()) {
            log.error("Webhook amount mismatch for orderId={}: webhook reports {} paise, PaymentOrder expects " +
                    "{} paise — refusing to settle.", orderId, amountPaise, paymentOrder.getAmount());
            return WebhookProcessingResult.acknowledge("amount mismatch");
        }
        if (currency != null && !EXPECTED_CURRENCY.equalsIgnoreCase(currency)) {
            log.error("Webhook currency mismatch for orderId={}: webhook reports {}, expected {} — refusing to settle.",
                    orderId, currency, EXPECTED_CURRENCY);
            return WebhookProcessingResult.acknowledge("currency mismatch");
        }

        PaymentSettlementService.SettlementResult result = paymentSettlementService.settle(
                orderId, paymentId, null, paymentOrder.getSchoolId(),
                PaymentSettlementService.SettlementSource.RAZORPAY_WEBHOOK);

        if (result.outcome() == PaymentSettlementService.Outcome.SETTLED) {
            log.info("Webhook recovered a lost client-verification callback for orderId={} paymentId={}.",
                    orderId, paymentId);
            sendSettlementNotifications(result.payment(), paymentOrder.getSchoolId());
        }

        return WebhookProcessingResult.acknowledge(result.message());
    }

    // ═══════════════════════════ Refund-Integrity Hardening, Phase C ═══════════════════════════

    /** The real Razorpay refund status vocabulary — verified directly against official Razorpay
     * documentation during the Phase B/C design validation, never invented. "pending" is a
     * legitimate value in the SYNCHRONOUS create-refund response itself (normal refunds can take
     * 5-7 business days; Razorpay may not resolve them instantly), not only a later webhook
     * state. "reversed" (a processed refund later reversed, e.g. a bank-side failure after
     * initial success) exists but has no clearly-documented dedicated webhook event name, so it
     * is handled defensively by inspecting the status field itself rather than assuming an event
     * name — see {@link #handleRefundWebhookEvent}. */
    public static final String PROVIDER_STATUS_PENDING = "pending";
    public static final String PROVIDER_STATUS_PROCESSED = "processed";
    public static final String PROVIDER_STATUS_FAILED = "failed";
    public static final String PROVIDER_STATUS_REVERSED = "reversed";

    /** Normalized, SDK-independent view of a Razorpay refund — the only refund shape exposed
     * outside this class, matching this codebase's existing convention of never leaking
     * {@code com.razorpay.*} SDK objects past RazorpayService's own boundary. {@code amountPaise}
     * and {@code currency} are nullable: not every response path is guaranteed to carry them,
     * and callers must not assume they're present. */
    public record ProviderRefundResult(String refundId, String status, String paymentId,
                                        Long amountPaise, String currency) {}

    private ProviderRefundResult toProviderRefundResult(com.razorpay.Refund refund) {
        Object id = refund.get("id");
        Object status = refund.get("status");
        Object paymentId = refund.get("payment_id");
        Object amount = refund.get("amount");
        Object currency = refund.get("currency");
        return new ProviderRefundResult(
                id != null ? String.valueOf(id) : null,
                status != null ? String.valueOf(status) : null,
                paymentId != null ? String.valueOf(paymentId) : null,
                amount instanceof Number ? ((Number) amount).longValue() : null,
                currency != null ? String.valueOf(currency) : null);
    }

    /**
     * Creates a refund via the Razorpay API. The returned status is whatever Razorpay actually
     * reported synchronously — {@code pending}, {@code processed}, or (less commonly at create
     * time) {@code failed} — never assumed or overwritten by the caller. Preserving the real
     * status is exactly what Phase C's reconciliation is built to react to; a caller must not
     * treat a non-throwing return as automatic success (see {@link PaymentService#processRefund}).
     *
     * @param razorpayPaymentId the Razorpay payment ID to refund
     * @param amountInPaise     refund amount in paise
     * @param reason            reason for the refund
     * @return the provider's refund id and real status
     */
    public ProviderRefundResult createRefund(String razorpayPaymentId, long amountInPaise, String reason) {
        try {
            RazorpayClient client = getRazorpayClient();
            JSONObject refundRequest = new JSONObject();
            refundRequest.put("amount", amountInPaise);
            JSONObject notes = new JSONObject();
            notes.put("reason", reason);
            refundRequest.put("notes", notes);

            com.razorpay.Refund refund = client.Payments.refund(razorpayPaymentId, refundRequest);
            ProviderRefundResult result = toProviderRefundResult(refund);
            log.info("Refund created. providerRefundId={} status={} for paymentId={} amount={}",
                    result.refundId(), result.status(), razorpayPaymentId, amountInPaise);
            return result;
        } catch (RazorpayException e) {
            log.error("Failed to create refund for paymentId={} amount={}", razorpayPaymentId, amountInPaise, e);
            throw new RuntimeException("Refund failed: " + e.getMessage(), e);
        }
    }

    /**
     * Fetches the current state of a previously-created refund directly from Razorpay — the
     * SDK's existing, unmodified {@code Payments.fetchRefund(String)} (GET
     * {@code /v1/refunds/{id}}), already present in the pinned 1.3.9 SDK; no new dependency, no
     * SDK upgrade, no undocumented API. This is the pull side of reconciliation
     * ({@link #reconcileRefund}); the webhook path never needs this call, since the webhook
     * payload already carries the same fields.
     */
    public ProviderRefundResult fetchRefund(String providerRefundId) {
        try {
            RazorpayClient client = getRazorpayClient();
            com.razorpay.Refund refund = client.Payments.fetchRefund(providerRefundId);
            return toProviderRefundResult(refund);
        } catch (RazorpayException e) {
            log.error("Failed to fetch refund state for providerRefundId={}", providerRefundId, e);
            throw new RuntimeException("Refund lookup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Pull-based reconciliation for one PENDING refund — the counterpart to webhook-driven
     * recovery, for a refund whose webhook was never delivered, failed delivery, or arrives
     * late. Phase D calls this from {@link RefundReconciliationJob}'s scheduled batch; no
     * authenticated HTTP endpoint exposes it directly (see the Phase D report for why one was
     * deliberately not added).
     * <p>
     * Never calls {@link #createRefund} again — if no {@code providerRefundId} is known yet
     * (the create call itself was ambiguous — an exception, no response ever received), there is
     * nothing safe to look up, and retrying create() could double-refund if the original request
     * actually reached Razorpay. That case is left PENDING and reported as a remaining
     * operational case requiring manual verification against the Razorpay dashboard.
     */
    public void reconcileRefund(Long refundId) {
        com.indraacademy.ias_management.entity.Refund refund = refundRepository.findById(refundId).orElse(null);
        if (refund == null) {
            log.warn("reconcileRefund: refund {} not found.", refundId);
            return;
        }
        if (!RefundSettlementService.STATUS_PENDING.equals(refund.getStatus())) {
            log.info("reconcileRefund: refund {} is already '{}' — nothing to reconcile.", refundId, refund.getStatus());
            return;
        }
        String providerRefundId = refund.getProviderRefundId();
        if (providerRefundId == null || providerRefundId.isBlank()) {
            log.warn("reconcileRefund: refund {} is PENDING with no providerRefundId — cannot safely reconcile " +
                    "without a known provider operation to look up; leaving PENDING. Requires manual verification " +
                    "against the Razorpay dashboard.", refundId);
            return;
        }

        ProviderRefundResult providerResult;
        try {
            providerResult = fetchRefund(providerRefundId);
        } catch (RuntimeException e) {
            // Deliberately treats every lookup failure identically — a network timeout, a 5xx,
            // and a 404-style "not found" all surface as the same RazorpayException from this
            // SDK (no structured status code to branch on; see the Phase B/C reports). A 404 for
            // an id this application itself received from a successful create call is an
            // operational anomaly, not proof the refund never existed — Task 9 explicitly
            // forbids treating it as equivalent to "never created." Conservative in every case:
            // stays PENDING, capacity remains reserved, logged for operator visibility, retried
            // on the next scheduled pass.
            log.warn("reconcileRefund: provider lookup failed for refund {} (providerRefundId={}) — leaving " +
                    "PENDING, capacity remains reserved. (Includes a not-found response, which is treated as an " +
                    "anomaly to investigate, never as proof the refund doesn't exist.)", refundId, providerRefundId, e);
            return;
        }

        try {
            refundSettlementService.resolveFromProviderState(providerResult.refundId(), providerResult.status(),
                    providerResult.paymentId(), providerResult.amountPaise(), providerResult.currency(),
                    "SYSTEM_RECONCILIATION", "SYSTEM", null);
        } catch (RuntimeException e) {
            // Mirrors the webhook path's own handling of the same call: a thrown finalize (e.g.
            // the allocation-ledger-inconsistency guard) rolls its own transaction back, so the
            // refund is already correctly still PENDING with providerRefundId intact — this
            // catch exists only so a caller of reconcileRefund (ops tooling, a future job) never
            // crashes on it; the next reconciliation attempt will retry finalize.
            log.error("reconcileRefund: resolving refund {} from provider state failed — remains PENDING for a " +
                    "later retry.", refundId, e);
        }
    }

    /**
     * Handles refund.* webhook events. Razorpay's refund webhook payload shape is
     * {@code payload.refund.entity} (never {@code payload.payment.entity}, which is what the
     * payment.* branch above reads) — both entities are documented to appear together, but the
     * refund-specific fields (id/status/payment_id/amount/currency) all live under
     * {@code refund.entity}. Reuses the exact same {@link RefundSettlementService#resolveFromProviderState}
     * state machine {@link #reconcileRefund} uses, so a webhook and a pull-reconciliation can
     * never disagree about what a given provider state means.
     */
    private WebhookProcessingResult handleRefundWebhookEvent(String eventType, JSONObject payloadObj) {
        JSONObject refundContainer = payloadObj.optJSONObject("refund");
        JSONObject refundEntity = refundContainer != null ? refundContainer.optJSONObject("entity") : null;
        if (refundEntity == null) {
            log.warn("Refund webhook event '{}' has no refund entity. Skipping.", eventType);
            return WebhookProcessingResult.acknowledge("no refund entity");
        }

        String providerRefundId = refundEntity.optString("id", null);
        if (providerRefundId == null || providerRefundId.isBlank()) {
            log.warn("Refund webhook event '{}' missing refund id. Skipping.", eventType);
            return WebhookProcessingResult.acknowledge("missing refund id");
        }
        String providerStatus = refundEntity.optString("status", null);
        String providerPaymentId = refundEntity.optString("payment_id", null);
        long amountRaw = refundEntity.optLong("amount", -1);
        Long providerAmountPaise = amountRaw >= 0 ? amountRaw : null;
        String providerCurrency = refundEntity.optString("currency", null);

        return switch (eventType) {
            case "refund.created" -> {
                log.info("Webhook: refund.created for providerRefundId={} — observational only, awaiting " +
                        "refund.processed/refund.failed before reconciling.", providerRefundId);
                yield WebhookProcessingResult.acknowledge("refund created (observational)");
            }
            case "refund.processed", "refund.failed" -> {
                try {
                    RefundSettlementService.ReconciliationResult result = refundSettlementService.resolveFromProviderState(
                            providerRefundId, providerStatus, providerPaymentId, providerAmountPaise, providerCurrency,
                            "RAZORPAY_WEBHOOK", "SYSTEM", null);
                    yield WebhookProcessingResult.acknowledge(result.message());
                } catch (Exception e) {
                    log.error("Unexpected error reconciling refund webhook for providerRefundId={} — requesting retry.",
                            providerRefundId, e);
                    yield WebhookProcessingResult.retry("internal error");
                }
            }
            default -> {
                log.info("Webhook: Unhandled refund event type '{}'. Ignoring.", eventType);
                yield WebhookProcessingResult.acknowledge("unhandled refund event type");
            }
        };
    }

    private String buildPaymentConfirmationHtml(String studentName, String paymentId,
                                                double amount, String session, String month, String schoolName) {
        String safeSchool = (schoolName != null && !schoolName.isBlank()) ? schoolName : "School";
        String formattedAmount = String.format("%.2f", amount);
        String safeSession = session != null ? session : "—";
        String safeMonth   = month   != null ? month   : "—";
        int year = java.time.LocalDate.now().getYear();
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Payment Confirmation</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f0fdf4;font-family:Arial,Helvetica,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f0fdf4;padding:32px 16px;">
                    <tr><td align="center">
                      <table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;">

                        <!-- Header -->
                        <tr>
                          <td align="center" style="background-color:#065f46;border-radius:16px 16px 0 0;padding:32px 40px 24px;">
                            <p style="margin:0 0 10px;font-size:48px;line-height:1;">&#10003;</p>
                            <h1 style="margin:0;color:#ffffff;font-size:24px;font-weight:800;">%s</h1>
                          </td>
                        </tr>

                        <!-- Band -->
                        <tr>
                          <td align="center" style="background-color:#059669;padding:10px 40px;">
                            <p style="margin:0;color:#ffffff;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;">
                              &#9989; Payment Successful
                            </p>
                          </td>
                        </tr>

                        <!-- Body -->
                        <tr>
                          <td style="background-color:#ffffff;padding:36px 40px;">
                            <p style="margin:0 0 20px;font-size:16px;color:#111827;">Dear <strong>%s</strong>,</p>
                            <p style="margin:0 0 28px;font-size:14px;color:#6b7280;line-height:1.8;">
                              Your school fee payment has been <strong style="color:#059669;">successfully processed</strong>.
                              Please find your payment summary below for your records.
                            </p>

                            <!-- Amount highlight -->
                            <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:24px;">
                              <tr>
                                <td align="center" style="background-color:#ecfdf5;border:2px solid #6ee7b7;border-radius:14px;padding:24px;">
                                  <p style="margin:0 0 6px;font-size:12px;font-weight:700;color:#059669;letter-spacing:1.5px;text-transform:uppercase;">Amount Paid</p>
                                  <p style="margin:0;font-size:36px;font-weight:800;color:#065f46;">&#8377; %s</p>
                                </td>
                              </tr>
                            </table>

                            <!-- Receipt table -->
                            <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;border:1px solid #d1fae5;border-radius:12px;overflow:hidden;">
                              <tr style="background-color:#f0fdf4;">
                                <td colspan="2" style="padding:12px 20px;font-size:11px;font-weight:700;color:#065f46;letter-spacing:1.2px;text-transform:uppercase;border-bottom:1px solid #d1fae5;">
                                  Transaction Details
                                </td>
                              </tr>
                              <tr>
                                <td style="padding:11px 20px;font-size:13px;color:#6b7280;font-weight:600;border-bottom:1px solid #f0fdf4;width:40%%;">Payment ID</td>
                                <td style="padding:11px 20px;font-size:12px;color:#111827;font-family:monospace;border-bottom:1px solid #f0fdf4;">%s</td>
                              </tr>
                              <tr style="background-color:#f9fafb;">
                                <td style="padding:11px 20px;font-size:13px;color:#6b7280;font-weight:600;border-bottom:1px solid #f0fdf4;">Academic Session</td>
                                <td style="padding:11px 20px;font-size:13px;color:#111827;font-weight:700;border-bottom:1px solid #f0fdf4;">%s</td>
                              </tr>
                              <tr>
                                <td style="padding:11px 20px;font-size:13px;color:#6b7280;font-weight:600;">Month</td>
                                <td style="padding:11px 20px;font-size:13px;color:#111827;font-weight:700;">%s</td>
                              </tr>
                            </table>

                            <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
                              <tr>
                                <td style="background-color:#fffbeb;border-left:4px solid #f59e0b;padding:14px 18px;border-radius:0 8px 8px 0;">
                                  <p style="margin:0;font-size:13px;color:#92400e;line-height:1.7;">
                                    &#128196; Please save this email as your fee payment receipt. You can also view
                                    payment history on the <strong>Edunexify</strong> website.
                                  </p>
                                </td>
                              </tr>
                            </table>

                            <hr style="border:none;border-top:1px solid #f1f5f9;margin:0 0 24px;">
                            <p style="margin:0;font-size:14px;color:#374151;line-height:1.7;">
                              Thank you,<br>
                              <strong>%s</strong><br>
                              <span style="font-size:12px;color:#9ca3af;">Fee Management Team</span>
                            </p>
                          </td>
                        </tr>

                        <!-- Footer -->
                        <tr>
                          <td align="center" style="background-color:#1f2937;border-radius:0 0 16px 16px;padding:20px 40px;">
                            <p style="margin:0 0 4px;font-size:12px;color:rgba(255,255,255,0.55);">This is an automated message. Please do not reply to this email.</p>
                            <p style="margin:0;font-size:11px;color:rgba(255,255,255,0.35);">&copy; %d %s. All rights reserved.</p>
                          </td>
                        </tr>

                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(safeSchool, studentName, formattedAmount, paymentId, safeSession, safeMonth, safeSchool, year, safeSchool);
    }
}
