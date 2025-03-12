package com.indraacademy.ias_management.controller;


import com.indraacademy.ias_management.service.RazorpayService;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;


@RestController
@RequestMapping("/api/payments")
@CrossOrigin(origins = "http://localhost:4200")
public class PaymentController {

    @Autowired
    private RazorpayService razorpayService;

    @PostMapping("/create")
    public Map<String, Object> createOrder(@RequestBody Map<String, Integer> data) {
        int amount = data.get("amount");
        return razorpayService.createOrder(amount);
    }

    @PostMapping("/verify")
    public Map<String, Object> verifyPayment(@RequestBody Map<String, String> paymentData) {
        return razorpayService.verifyPayment(paymentData);
    }
}
