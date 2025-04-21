package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class RazorpayService {

    @Autowired
    private PaymentRepository paymentRepository;

    private static final String KEY_ID = "rzp_test_uzFJONVXH4vqou";
    private static final String KEY_SECRET = "Ykv9bqCiYKyxz6y0OSWwwKX4";

    private final RazorpayClient razorpayClient;

    public RazorpayService() throws Exception {
        this.razorpayClient = new RazorpayClient(KEY_ID, KEY_SECRET);
    }

    public Map<String, Object> createOrder(int amount, String studentId, String studentName, String className, String session, String month, int busFee, int tuitionFee, int annualCharges, int labCharges, int ecaProject, int examinationFee) {
        try {
            JSONObject options = new JSONObject();
            options.put("amount", amount); // Amount in paisa
            options.put("currency", "INR");
            options.put("receipt", "txn_12345");
            options.put("payment_capture", 1); // Auto capture

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
                payment.setAmountPaid((Integer) orderDetails.get("amount")/100);
                payment.setRazorpaySignature(signature);
                System.out.println("working for hari");

                paymentRepository.save(payment);
                response.put("success", true);
                response.put("message", "Payment Verified Successfully");
            } else {
                response.put("success", false);
                response.put("message", "Payment Verification Failed");
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Error Verifying Payment");
        }
        return response;
    }
}
