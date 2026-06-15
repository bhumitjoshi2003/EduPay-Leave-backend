package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    @Autowired private StudentRepository studentRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private AttendanceService attendanceService;
    @Autowired private EmailService emailService;
    @Autowired private StudentFeesService studentFeesService;
    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private FeeStructureService feeStructureService;
    @Autowired private NotificationService notificationService;
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
     * Counts the number of unpaid months and multiplies by an estimated per-month fee.
     * This is a ceiling-based validation: the actual payment can be less (partial payment)
     * but never more than the total outstanding.
     */
    public long calculateOutstandingBalancePaise(String studentId, String session) {
        Long schoolId = securityUtil.getSchoolId();
        List<com.indraacademy.ias_management.entity.StudentFees> unpaidFees =
                studentFeesRepository.findByStudentIdAndSchoolIdAndPaidFalse(studentId, schoolId);

        if (unpaidFees.isEmpty()) {
            return 0;
        }

        // Filter to the requested session only
        List<com.indraacademy.ias_management.entity.StudentFees> sessionUnpaid = unpaidFees.stream()
                .filter(f -> session.equals(f.getYear()))
                .toList();

        if (sessionUnpaid.isEmpty()) {
            return 0;
        }

        // Estimate a generous upper bound per unpaid month using fee structure + bus fees.
        // We sum tuition + annual charges + lab + eca + exam + a bus fee estimate + late fee buffer.
        // This gives a ceiling that the client amount must not exceed.
        String className = sessionUnpaid.get(0).getClassName();
        long perMonthEstimatePaise = 0;

        try {
            com.indraacademy.ias_management.entity.FeeStructure feeStructure =
                    feeStructureService.getFeeStructuresByAcademicYearAndClassName(session, className);
            if (feeStructure != null) {
                // Fee structure amounts are in rupees (double), convert to paise
                perMonthEstimatePaise = Math.round(feeStructure.getTuitionFee() * 100.0)
                        + Math.round(feeStructure.getAnnualCharges() * 100.0)
                        + Math.round(feeStructure.getLabCharges() * 100.0)
                        + Math.round(feeStructure.getEcaProject() * 100.0)
                        + Math.round(feeStructure.getExaminationFee() * 100.0);
            }
        } catch (Exception e) {
            log.warn("Could not load fee structure for validation. studentId={} session={} class={}",
                    studentId, session, className, e);
        }

        // Add a generous bus fee estimate (max reasonable bus fee per month)
        long busFeeEstimatePaise = 5000_00L; // Rs 5000 max per month as safety ceiling

        // Add late fee buffer per month
        long lateFeeCeilingPaise = 30L * 21L * 100L; // 30 days * Rs 21/day max late fee tier

        long totalCeiling = 0;
        for (int i = 0; i < sessionUnpaid.size(); i++) {
            totalCeiling += perMonthEstimatePaise + busFeeEstimatePaise + lateFeeCeilingPaise;
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

            String schoolName = schoolRepository.findById(securityUtil.getSchoolId() != null ? securityUtil.getSchoolId() : -1L)
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

    public Map<String, Object> verifyPayment(Map<String, String> paymentData, Map<String, Object> orderDetails) {
        Map<String, Object> response = new HashMap<>();
        String paymentId = paymentData != null ? paymentData.get("razorpay_payment_id") : null;
        String orderId = paymentData != null ? paymentData.get("razorpay_order_id") : null;
        String signature = paymentData != null ? paymentData.get("razorpay_signature") : null;
        String studentId = orderDetails != null ? (String) orderDetails.get("studentId") : "N/A";

        log.info("Starting payment verification for Order ID: {} and Student ID: {}", orderId, studentId);

        if (paymentId == null || orderId == null || signature == null) {
            log.error("Payment verification data is incomplete. Order ID: {}", orderId);
            response.put("success", false);
            response.put("message", "Payment Verification Failed: Missing required fields.");
            return response;
        }

        String payload = null;
        try {
            // 1. Signature Verification
            payload = orderId + "|" + paymentId;
            boolean isValid = Utils.verifySignature(payload, signature, resolveKeySecret());

            if (!isValid) {
                log.warn("Signature verification failed for Order ID: {}", orderId);
                response.put("success", false);
                response.put("message", "Payment Verification Failed: Invalid Signature");
                return response;
            }
            log.info("Signature verified successfully for Payment ID: {}", paymentId);

            // 2. Idempotency check — if this paymentId was already persisted, return success
            //    without creating a duplicate record (handles client retries gracefully).
            if (paymentRepository.existsByPaymentId(paymentId)) {
                log.warn("Duplicate verify call for Payment ID: {} — already persisted, returning success.", paymentId);
                response.put("success", true);
                response.put("message", "Payment already verified.");
                return response;
            }

            // 3. Data Persistence and Post-Payment Logic (only if signature is valid)
            Payment payment = new Payment();
            payment.setStudentId(studentId);
            payment.setStudentName((String) orderDetails.get("studentName"));
            payment.setClassName((String) orderDetails.get("className"));
            payment.setSession((String) orderDetails.get("session"));
            payment.setMonth((String) orderDetails.get("month"));
            // Store amount in paise directly — never use floating-point for money.
            // The Payment entity's int fields now hold paise values.
            Integer amountInPaise = (Integer) orderDetails.get("amount");
            payment.setAmount(amountInPaise); // Stored in paise
            payment.setPaymentId(paymentId);
            payment.setOrderId(orderId);
            payment.setBusFee((Integer) orderDetails.get("busFee"));
            payment.setTuitionFee((Integer) orderDetails.get("tuitionFee"));
            payment.setAnnualCharges((Integer) orderDetails.get("annualCharges"));
            payment.setLabCharges((Integer) orderDetails.get("labCharges"));
            payment.setEcaProject((Integer) orderDetails.get("ecaProject"));
            payment.setExaminationFee((Integer) orderDetails.get("examinationFee"));
            payment.setPaidManually(false);
            payment.setAmountPaid(amountInPaise); // Stored in paise
            payment.setRazorpaySignature(signature);
            payment.setAdditionalCharges((Integer) orderDetails.get("additionalCharges"));
            payment.setLateFees((Integer) orderDetails.get("lateFees"));
            payment.setPlatformFee((Integer) orderDetails.get("platformFee"));
            payment.setSchoolId(securityUtil.getSchoolId());

            Payment savedPayment = paymentRepository.save(payment);
            log.info("Payment saved successfully to DB. Record ID: {}", savedPayment.getId());

            // 3. Update related services
            attendanceService.updateChargePaidAfterPayment(studentId, (String) orderDetails.get("session"));
            studentFeesService.markFeesAsPaid(payment);
            log.debug("Attendance and StudentFees marked as paid.");

            // 4. Notification — convert paise to rupees for display only
            double displayAmountRupees = amountInPaise / 100.0;
            String successNotificationMessage = String.format("Your fee payment of ₹%.2f has been successfully processed. Payment ID: %s", displayAmountRupees, paymentId);
            // Assuming Long.valueOf(savedPayment.getId()) is the correct type based on previous services
            String relatedEntityId = (savedPayment.getId() != null) ? String.valueOf(savedPayment.getId()) : null;

            notificationService.createAutoGeneratedIndividualNotification(
                    "Payment Successful",
                    successNotificationMessage,
                    "Successful Payment",
                    studentId,
                    "Payment",
                    relatedEntityId
            );
            log.info("Payment success notification initiated for student ID: {}", studentId);

            // 5. Asynchronous Email Sending
            Optional<Student> studentOptional = studentRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId());

            if (studentOptional.isPresent()) {
                Student student = studentOptional.get();
                String studentEmail = student.getEmail();
                String studentName = student.getName();

                if (studentEmail != null && !studentEmail.trim().isEmpty()) {
                    String subject     = "Payment Confirmation – Fee Receipt";
                    String session     = (String) orderDetails.get("session");
                    String monthBitmask = (String) orderDetails.get("month");
                    String monthNames  = convertMonthBitmask(monthBitmask);
                    String schoolName = schoolRepository.findById(securityUtil.getSchoolId())
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

    private static final String[] ACADEMIC_MONTHS = {
        "April", "May", "June", "July", "August", "September",
        "October", "November", "December", "January", "February", "March"
    };

    /** Converts a 12-char bitmask like "010000000000" to "May", or "April, May" for multi-month. */
    private String convertMonthBitmask(String bitmask) {
        if (bitmask == null || bitmask.isBlank()) return "—";
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < Math.min(bitmask.length(), 12); i++) {
            if (bitmask.charAt(i) == '1') selected.add(ACADEMIC_MONTHS[i]);
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