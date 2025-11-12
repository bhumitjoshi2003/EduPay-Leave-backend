package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
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

    // IMPORTANT: In a real-world application, these should be loaded securely from environment variables,
    // NOT hardcoded, and the keys should be production keys if live.
    private static final String KEY_ID = "rzp_test_uzFJONVXH4vqou";
    private static final String KEY_SECRET = "Ykv9bqCiYKyxz6y0OSWwwKX4";

    private final RazorpayClient razorpayClient;

    public RazorpayService() throws RazorpayException {
        try {
            this.razorpayClient = new RazorpayClient(KEY_ID, KEY_SECRET);
            log.info("Razorpay Client initialized successfully.");
        } catch (RazorpayException e) {
            log.error("Failed to initialize Razorpay Client. Check API keys.", e);
            // Re-throw as RuntimeException or let the container handle it
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
            boolean isValid = Utils.verifySignature(payload, signature, KEY_SECRET);

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
                    String subject = "Payment Confirmation: Your recent fee payment was successful!";
                    String body = "Dear " + studentName + ",\n\n"
                            + "Your payment for fees has been successfully processed.\n"
                            + "Payment ID: " + paymentId + "\n"
                            + "Amount Paid: INR " + amountInRupees + "\n"
                            + "Thank you for the payment!\n\n"
                            + "Regards,\nIndra Academy";

                    log.info("Initiating asynchronous email send to {} for payment verification.", studentEmail);
                    emailService.sendEmail(studentEmail, subject, body);
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
}