package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class RazorpayService {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private AttendanceService attendanceService;
    @Autowired private EmailService emailService;
    @Autowired private StudentFeesService studentFeesService;
    @Autowired private NotificationService notificationService;

    private static final String KEY_ID = "rzp_test_uzFJONVXH4vqou";
    private static final String KEY_SECRET = "Ykv9bqCiYKyxz6y0OSWwwKX4";

    private final RazorpayClient razorpayClient;

    public RazorpayService() throws Exception {
        this.razorpayClient = new RazorpayClient(KEY_ID, KEY_SECRET);
    }

    public Map<String, Object> createOrder(int amount, String studentId, String studentName, String className, String session, String month, Integer busFee, int tuitionFee, int annualCharges, int labCharges, int ecaProject, int examinationFee, int additionalCharges, int lateFees) {
        try {
            JSONObject options = new JSONObject();
            options.put("amount", amount);
            options.put("currency", "INR");
            options.put("receipt", "txn_12345");
            options.put("payment_capture", 1);

            Order order = razorpayClient.Orders.create(options);
            Map<String, Object> response = new HashMap<>();
            response.put("orderId", order.get("id"));
            response.put("amount", order.get("amount"));
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
            response.put("amountPaid", order.get("amount"));
            response.put("additionalCharges", additionalCharges);
            response.put("lateFees", lateFees);
            return response;
        } catch (Exception e) {
            throw new RuntimeException("Razorpay Order Creation Failed", e);
        }
    }

    public Map<String, Object> verifyPayment(Map<String, String> paymentData, Map<String, Object> orderDetails) {
        Map<String, Object> response = new HashMap<>();
        try {
            String paymentId = paymentData.get("razorpay_payment_id");
            String orderId = paymentData.get("razorpay_order_id");
            String signature = paymentData.get("razorpay_signature");

            String payload = orderId + "|" + paymentId;
            boolean isValid = Utils.verifySignature(payload, signature, KEY_SECRET);

            if (isValid) {
                Payment payment = new Payment();
                payment.setStudentId((String) orderDetails.get("studentId"));
                payment.setStudentName((String) orderDetails.get("studentName"));
                payment.setClassName((String) orderDetails.get("className"));
                payment.setSession((String) orderDetails.get("session"));
                payment.setMonth((String) orderDetails.get("month"));
                payment.setAmount((Integer) orderDetails.get("amount")/100);
                payment.setPaymentId(paymentId);
                payment.setOrderId(orderId);
                payment.setBusFee((Integer) orderDetails.get("busFee"));
                payment.setTuitionFee((Integer) orderDetails.get("tuitionFee"));
                payment.setAnnualCharges((Integer) orderDetails.get("annualCharges"));
                payment.setLabCharges((Integer) orderDetails.get("labCharges"));
                payment.setEcaProject((Integer) orderDetails.get("ecaProject"));
                payment.setExaminationFee((Integer) orderDetails.get("examinationFee"));
                payment.setPaidManually(false);
                payment.setAmountPaid((Integer) orderDetails.get("amount")/100); // Amount is in paisa from Razorpay
                payment.setRazorpaySignature(signature);
                payment.setAdditionalCharges((Integer) orderDetails.get("additionalCharges"));
                payment.setLateFees((Integer) orderDetails.get("lateFees"));

                Payment savedPayment = paymentRepository.save(payment);
                response.put("success", true);
                response.put("message", "Payment Verified Successfully");

                attendanceService.updateChargePaidAfterPayment((String) orderDetails.get("studentId"), (String) orderDetails.get("session"));

                studentFeesService.markFeesAsPaid(payment);

                Integer amountPaidInPaisa = (Integer) orderDetails.get("amount");
                double amountPaid = amountPaidInPaisa / 100.0;

                // --- Notification for Successful Payment ---
                String successNotificationMessage = String.format("Your payment of INR %.2f has been successfully processed.", amountPaid);
                notificationService.createAutoGeneratedIndividualNotification(
                        "Payment Successful",              // Title
                        successNotificationMessage,        // Message
                        "Successful Payment",      // Type
                        (String) orderDetails.get("studentId"),                         // User ID of the student
                        "Payment",                         // relatedEntityType
                        (savedPayment.getId() != null) ? Long.valueOf(savedPayment.getId()) : null // Pass Payment ID, assuming it's convertible to Long, or null if not applicable/generated
                );
                // --- End Notification ---

                // --- Asynchronous Email Sending ---
                String studentId = (String) orderDetails.get("studentId");
                Optional<Student> studentOptional = studentRepository.findById(studentId);

                if (studentOptional.isPresent()) {
                    Student student = studentOptional.get();
                    String studentEmail = student.getEmail();
                    String studentName = student.getName(); // Use actual student name from DB

                    if (studentEmail != null && !studentEmail.trim().isEmpty()) {
                        String subject = "Payment Confirmation: Your recent fee payment was successful!";
                        String body = "Dear " + studentName + ",\n\n"
                                + "Your payment for fees has been successfully processed.\n"
                                + "Payment ID: " + paymentId + "\n"
                                + "Amount Paid: INR " + ((Integer) orderDetails.get("amount") / 100.0) + "\n"
                                + "Thank you for the payment!\n\n"
                                + "Regards,\nIndra Academy";

                        System.out.println("Payment verified successfully. Initiating email send for " + studentEmail);
                        emailService.sendEmail(studentEmail, subject, body);
                        System.out.println("Email sending process initiated (asynchronously). Returning verification result.");
                    } else {
                        System.out.println("Student email not found or is empty for student ID: " + studentId + ". Skipping email notification.");
                    }
                } else {
                    System.out.println("Student not found for ID: " + studentId + ". Skipping email notification.");
                }
                // --- End Asynchronous Email Sending ---

            } else {
                response.put("success", false);
                response.put("message", "Payment Verification Failed");
            }
        } catch (Exception e) {
            System.err.println("Error Verifying Payment: " + e.getMessage());
            response.put("success", false);
            response.put("message", "Error Verifying Payment");
        }
        return response;
    }
}