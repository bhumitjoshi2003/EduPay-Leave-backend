package com.indraacademy.ias_management.service;

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
    @Autowired private AttendanceService attendanceService;
    @Autowired private EmailService emailService;
    @Autowired private StudentFeesService studentFeesService;
    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private BusinessNotificationService businessNotifications;
    @Autowired private FeeCalculationService feeCalculationService;
    @Autowired private SecurityUtil securityUtil;

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

    public Map<String, Object> createOrder(int amount, String studentId, String studentName, String className, String session, String month, Integer busFee, int tuitionFee, int annualCharges, int labCharges, int ecaProject, int examinationFee, int additionalCharges, int lateFees, int platformFee) {
        if (amount <= 0 || studentId == null || studentId.trim().isEmpty()) {
            log.warn("Attempted to create order with invalid amount or missing student ID. Amount: {}", amount);
            throw new IllegalArgumentException("Invalid amount or missing student ID for order creation.");
        }

        // Amount is expected in paisa by Razorpay, but passed as an int representing paisa here.
        log.info("Creating Razorpay order for student ID: {} with amount: {} paisa", studentId, amount);

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
            Long schoolId = securityUtil.getSchoolId();
            PaymentOrder paymentOrder = new PaymentOrder();
            paymentOrder.setOrderId(orderId);
            paymentOrder.setSchoolId(schoolId);
            paymentOrder.setStudentId(studentId);
            paymentOrder.setClassName(className);
            paymentOrder.setSession(session);
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
            paymentOrder.setPlatformFee(platformFee);
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
            response.put("amountPaid", order.get("amount")); // Amount in paisa
            response.put("additionalCharges", additionalCharges);
            response.put("lateFees", lateFees);
            response.put("platformFee", platformFee);

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

        String payload = null;
        try {
            // 1. Signature Verification — proves a real payment was captured for this
            //    orderId. It does NOT prove which student/amount/months that order was
            //    for — that's what the lookup below establishes, from what WE persisted
            //    when the order was created, never from client-supplied data.
            payload = orderId + "|" + paymentId;
            boolean isValid = Utils.verifySignature(payload, signature, resolveKeySecret());

            if (!isValid) {
                log.warn("Signature verification failed for Order ID: {}", orderId);
                response.put("success", false);
                response.put("message", "Payment Verification Failed: Invalid Signature");
                return response;
            }
            log.info("Signature verified successfully for Payment ID: {}", paymentId);

            // 2. Idempotency check — if this exact paymentId was already persisted, return
            //    success without creating a duplicate record (handles client retries).
            if (paymentRepository.existsByPaymentId(paymentId)) {
                log.warn("Duplicate verify call for Payment ID: {} — already persisted, returning success.", paymentId);
                response.put("success", true);
                response.put("message", "Payment already verified.");
                return response;
            }

            // 3. Look up the server-persisted order — the actual source of truth for
            //    studentId/amount/months/class/session. A forged or unknown orderId
            //    (one this school never created via /create) is rejected here.
            PaymentOrder paymentOrder = paymentOrderRepository.findByOrderId(orderId).orElse(null);
            if (paymentOrder == null) {
                log.error("No server-side order record found for Order ID: {} — rejecting.", orderId);
                response.put("success", false);
                response.put("message", "Payment Verification Failed: Unknown order.");
                return response;
            }
            Long schoolId = securityUtil.getSchoolId();
            if (!paymentOrder.getSchoolId().equals(schoolId)) {
                log.error("Order {} belongs to schoolId={} but caller is schoolId={} — rejecting.",
                        orderId, paymentOrder.getSchoolId(), schoolId);
                response.put("success", false);
                response.put("message", "Payment Verification Failed: Order does not belong to this school.");
                return response;
            }
            if (paymentOrder.isConsumed()) {
                // Already-consumed orders with a DIFFERENT paymentId than what consumed
                // them (the identical-paymentId retry case was already handled by the
                // existsByPaymentId check above) — a second, different payment being
                // matched against an already-used order is exactly the replay this table
                // exists to prevent.
                log.error("Order {} was already consumed — rejecting reuse with Payment ID: {}.", orderId, paymentId);
                response.put("success", false);
                response.put("message", "Payment Verification Failed: Order already used.");
                return response;
            }

            String studentId = paymentOrder.getStudentId();
            Optional<Student> studentOptional = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId);
            String studentName = studentOptional.map(Student::getName).orElse(studentId);

            // 4. Data Persistence and Post-Payment Logic — every business field below
            //    comes from paymentOrder (server-trusted), never from the orderDetails
            //    map the client sends alongside the verify request.
            Payment payment = new Payment();
            payment.setStudentId(studentId);
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

            Payment savedPayment;
            try {
                savedPayment = paymentRepository.save(payment);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // A concurrent request (e.g. a duplicate webhook firing alongside this
                // client-triggered verify) already saved a Payment with this exact
                // payment_id between the existsByPaymentId check above and this save — the
                // DB unique constraint on payment.payment_id (added this phase) is the real
                // backstop for that race. Treat it the same as the early existsByPaymentId
                // short-circuit: already processed, not a failure.
                log.warn("Duplicate payment save race detected for paymentId={} — already recorded by a concurrent request.", paymentId, e);
                response.put("success", true);
                response.put("message", "Payment already verified.");
                return response;
            }
            log.info("Payment saved successfully to DB. Record ID: {}", savedPayment.getId());

            paymentOrder.setConsumed(true);
            paymentOrderRepository.save(paymentOrder);

            // 3. Update related services
            attendanceService.updateChargePaidAfterPayment(studentId, paymentOrder.getSession());
            studentFeesService.markFeesAsPaid(payment);
            log.debug("Attendance and StudentFees marked as paid.");

            // 4. Notification — convert paise to rupees for display only
            double displayAmountRupees = amountInPaise / 100.0;
            String successNotificationMessage = String.format("Your fee payment of ₹%.2f has been successfully processed. Payment ID: %s", displayAmountRupees, paymentId);
            // Assuming Long.valueOf(savedPayment.getId()) is the correct type based on previous services
            String relatedEntityId = (savedPayment.getId() != null) ? String.valueOf(savedPayment.getId()) : null;

            businessNotifications.studentAndParents(schoolId, studentId,
                    NotificationAudienceType.STUDENT_WITH_FEE_PARENTS,
                    NotificationEventCode.PAYMENT_SUCCESS, NotificationCategory.FEES_PAYMENTS,
                    "Payment Successful", successNotificationMessage, "Payment", relatedEntityId,
                    "/dashboard/payment-history", studentId, "payment-success:" + paymentId,
                    java.util.Set.of(ExternalDeliveryChannel.PUSH));
            log.info("Payment success notification initiated for student ID: {}", studentId);

            // 5. Asynchronous Email Sending
            if (studentOptional.isPresent()) {
                Student student = studentOptional.get();
                String studentEmail = student.getEmail();

                if (studentEmail != null && !studentEmail.trim().isEmpty()) {
                    String subject     = "Payment Confirmation – Fee Receipt";
                    String session     = paymentOrder.getSession();
                    String monthBitmask = paymentOrder.getMonth();
                    String monthNames  = convertMonthBitmask(monthBitmask, schoolId);
                    String schoolName = schoolRepository.findById(schoolId)
                            .map(com.indraacademy.ias_management.entity.School::getName).orElse("School");
                    String htmlBody = buildPaymentConfirmationHtml(studentName, paymentId, displayAmountRupees, session, monthNames, schoolName);

                    log.info("Initiating asynchronous HTML email send to {} for payment verification.", studentEmail);
                    emailService.sendHtmlEmail(studentEmail, subject, htmlBody);
                } else {
                    log.warn("Student email not found or is empty for student ID: {}. Skipping email notification.", studentId);
                }
            } else {
                log.warn("Student not found for ID: {}. Skipping email notification.", studentId);
            }

            response.put("success", true);
            response.put("message", "Payment Verified Successfully");
            return response;

        } catch (RazorpayException e) {
            log.error("Razorpay signature verification failed for Order ID: {}. Payload: {}", orderId, payload, e);
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

    /**
     * Processes a Razorpay webhook event. Called from the webhook controller.
     * Returns true if the event was processed successfully or was already handled (idempotent).
     */
    public boolean processWebhookEvent(String payload) {
        try {
            JSONObject event = new JSONObject(payload);
            String eventType = event.optString("event", "");
            JSONObject paymentEntity = event.optJSONObject("payload");

            if (paymentEntity == null) {
                log.warn("Webhook event has no payload. Event type: {}", eventType);
                return false;
            }

            JSONObject paymentObj = paymentEntity.optJSONObject("payment");
            if (paymentObj == null) {
                log.info("Webhook event type '{}' has no payment object. Skipping.", eventType);
                return true;
            }

            JSONObject entity = paymentObj.optJSONObject("entity");
            if (entity == null) {
                log.warn("Webhook payment object has no entity. Event type: {}", eventType);
                return false;
            }

            String razorpayPaymentId = entity.optString("id", null);
            String razorpayOrderId = entity.optString("order_id", null);
            String status = entity.optString("status", "");

            switch (eventType) {
                case "payment.authorized":
                case "payment.captured":
                    log.info("Webhook: {} for paymentId={} orderId={}", eventType, razorpayPaymentId, razorpayOrderId);
                    // Idempotency check — if already recorded, skip
                    if (razorpayPaymentId != null && paymentRepository.existsByPaymentId(razorpayPaymentId)) {
                        log.info("Webhook: Payment {} already recorded. Skipping.", razorpayPaymentId);
                        return true;
                    }
                    // Log for manual follow-up if the payment wasn't recorded via the verify endpoint
                    log.warn("Webhook: Payment {} (order {}) was {} but not yet recorded via verify endpoint. " +
                            "Manual reconciliation may be needed.", razorpayPaymentId, razorpayOrderId, eventType);
                    break;

                case "payment.failed":
                    log.warn("Webhook: Payment failed. paymentId={} orderId={} status={}",
                            razorpayPaymentId, razorpayOrderId, status);
                    break;

                default:
                    log.info("Webhook: Unhandled event type '{}'. Ignoring.", eventType);
            }

            return true;
        } catch (Exception e) {
            log.error("Error processing webhook event.", e);
            return false;
        }
    }

    /**
     * Creates a refund via the Razorpay API.
     *
     * @param razorpayPaymentId the Razorpay payment ID to refund
     * @param amountInPaise     refund amount in paise
     * @param reason            reason for the refund
     * @return a map with refund details (id, amount, status)
     */
    public Map<String, Object> createRefund(String razorpayPaymentId, long amountInPaise, String reason) {
        try {
            RazorpayClient client = getRazorpayClient();
            JSONObject refundRequest = new JSONObject();
            refundRequest.put("amount", amountInPaise);
            JSONObject notes = new JSONObject();
            notes.put("reason", reason);
            refundRequest.put("notes", notes);

            com.razorpay.Refund refund = client.Payments.refund(razorpayPaymentId, refundRequest);

            Map<String, Object> result = new HashMap<>();
            result.put("refundId", refund.get("id"));
            result.put("amount", refund.get("amount"));
            result.put("status", refund.get("status"));
            log.info("Refund created successfully. RefundId={} for PaymentId={} amount={}",
                    refund.get("id"), razorpayPaymentId, amountInPaise);
            return result;
        } catch (RazorpayException e) {
            log.error("Failed to create refund for paymentId={} amount={}", razorpayPaymentId, amountInPaise, e);
            throw new RuntimeException("Refund failed: " + e.getMessage(), e);
        }
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
