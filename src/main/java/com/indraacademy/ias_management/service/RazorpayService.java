package com.indraacademy.ias_management.service;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class RazorpayService {

    private static final String KEY_ID = "rzp_test_uzFJONVXH4vqou";
    private static final String KEY_SECRET = "Ykv9bqCiYKyxz6y0OSWwwKX4";

    private final RazorpayClient razorpayClient;

    public RazorpayService() throws Exception {
        this.razorpayClient = new RazorpayClient(KEY_ID, KEY_SECRET);
    }

    public Map<String, Object> createOrder(int amount) {
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
            return response;
        } catch (Exception e) {
            throw new RuntimeException("Razorpay Order Creation Failed", e);
        }
    }

    public Map<String, Object> verifyPayment(Map<String, String> paymentData) {
        Map<String, Object> response = new HashMap<>();
        try {
            String paymentId = paymentData.get("razorpay_payment_id");
            String orderId = paymentData.get("razorpay_order_id");
            String signature = paymentData.get("razorpay_signature");

            String payload = orderId + "|" + paymentId;
            boolean isValid = Utils.verifySignature(payload, signature, KEY_SECRET);

            if (isValid) {
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
