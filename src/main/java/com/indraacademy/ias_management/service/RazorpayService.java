package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import jakarta.annotation.PostConstruct;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class RazorpayService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayService.class);

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private AttendanceService attendanceService;
    @Autowired private EmailService emailService;
    @Autowired private StudentFeesService studentFeesService;
    @Autowired private NotificationService notificationService;

    @Value("${razorpay.key.id}")
    private String keyId;

    @Value("${razorpay.key.secret}")
    private String keySecret;

    private RazorpayClient razorpayClient;

    @PostConstruct
    public void init() {
        try {
            this.razorpayClient = new RazorpayClient(keyId, keySecret);
            log.info("Razorpay Client initialized successfully.");
        } catch (RazorpayException e) {
            log.error("Failed to initialize Razorpay Client.", e);
            throw new RuntimeException("Failed to initialize Razorpay client", e);
        }
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

            Order order = razorpayClient.Orders.create(options);

            Map<String, Object> response = new HashMap<>();
            response.put("razorpayKey", this.keyId);
            response.put("orderId", order.get("id"));
            response.put("amount", order.get("amount")); // Amount in paisa
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
            boolean isValid = Utils.verifySignature(payload, signature, keySecret);

            if (!isValid) {
                log.warn("Signature verification failed for Order ID: {}", orderId);
                response.put("success", false);
                response.put("message", "Payment Verification Failed: Invalid Signature");
                return response;
            }
            log.info("Signature verified successfully for Payment ID: {}", paymentId);

            // 2. Data Persistence and Post-Payment Logic (only if signature is valid)
            Payment payment = new Payment();
            payment.setStudentId(studentId);
            payment.setStudentName((String) orderDetails.get("studentName"));
            payment.setClassName((String) orderDetails.get("className"));
            payment.setSession((String) orderDetails.get("session"));
            payment.setMonth((String) orderDetails.get("month"));
            // Convert amount from paisa (Razorpay's unit) to Rupees
            Integer amountInPaisa = (Integer) orderDetails.get("amount");
            double amountInRupees = amountInPaisa / 100.0;
            payment.setAmount((int) amountInRupees); // Storing total billable amount
            payment.setPaymentId(paymentId);
            payment.setOrderId(orderId);
            payment.setBusFee((Integer) orderDetails.get("busFee"));
            payment.setTuitionFee((Integer) orderDetails.get("tuitionFee"));
            payment.setAnnualCharges((Integer) orderDetails.get("annualCharges"));
            payment.setLabCharges((Integer) orderDetails.get("labCharges"));
            payment.setEcaProject((Integer) orderDetails.get("ecaProject"));
            payment.setExaminationFee((Integer) orderDetails.get("examinationFee"));
            payment.setPaidManually(false);
            payment.setAmountPaid((int) amountInRupees); // Storing paid amount
            payment.setRazorpaySignature(signature);
            payment.setAdditionalCharges((Integer) orderDetails.get("additionalCharges"));
            payment.setLateFees((Integer) orderDetails.get("lateFees"));
            payment.setPlatformFee((Integer) orderDetails.get("platformFee"));

            Payment savedPayment = paymentRepository.save(payment);
            log.info("Payment saved successfully to DB. Record ID: {}", savedPayment.getId());

            // 3. Update related services
            attendanceService.updateChargePaidAfterPayment(studentId, (String) orderDetails.get("session"));
            studentFeesService.markFeesAsPaid(payment);
            log.debug("Attendance and StudentFees marked as paid.");

            // 4. Notification
            String successNotificationMessage = String.format("Your fee payment of ₹%.2f has been successfully processed. Payment ID: %s", amountInRupees, paymentId);
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
            Optional<Student> studentOptional = studentRepository.findById(studentId);

            if (studentOptional.isPresent()) {
                Student student = studentOptional.get();
                String studentEmail = student.getEmail();
                String studentName = student.getName();

                if (studentEmail != null && !studentEmail.trim().isEmpty()) {
                    String subject  = "Payment Confirmation – Fee Receipt";
                    String session  = (String) orderDetails.get("session");
                    String month    = (String) orderDetails.get("month");
                    String htmlBody = buildPaymentConfirmationHtml(studentName, paymentId, amountInRupees, session, month);

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

    private String buildPaymentConfirmationHtml(String studentName, String paymentId,
                                                double amount, String session, String month) {
        String formattedAmount = String.format("%.2f", amount);
        String safeSession = session != null ? session : "—";
        String safeMonth   = month   != null ? month   : "—";
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
                            <h1 style="margin:0;color:#ffffff;font-size:24px;font-weight:800;">Indra Academy</h1>
                            <p style="margin:6px 0 0;color:rgba(255,255,255,0.75);font-size:13px;">Sr. Sec. School</p>
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
                                    payment history in the <strong>Indra Academy EduPay</strong> app.
                                  </p>
                                </td>
                              </tr>
                            </table>

                            <hr style="border:none;border-top:1px solid #f1f5f9;margin:0 0 24px;">
                            <p style="margin:0;font-size:14px;color:#374151;line-height:1.7;">
                              Thank you,<br>
                              <strong>Indra Academy Sr. Sec. School</strong><br>
                              <span style="font-size:12px;color:#9ca3af;">Fee Management Team</span>
                            </p>
                          </td>
                        </tr>

                        <!-- Footer -->
                        <tr>
                          <td align="center" style="background-color:#1f2937;border-radius:0 0 16px 16px;padding:20px 40px;">
                            <p style="margin:0 0 4px;font-size:12px;color:rgba(255,255,255,0.55);">This is an automated message. Please do not reply to this email.</p>
                            <p style="margin:0;font-size:11px;color:rgba(255,255,255,0.35);">&copy; 2026 Indra Academy Sr. Sec. School. All rights reserved.</p>
                          </td>
                        </tr>

                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(studentName, formattedAmount, paymentId, safeSession, safeMonth);
    }
}