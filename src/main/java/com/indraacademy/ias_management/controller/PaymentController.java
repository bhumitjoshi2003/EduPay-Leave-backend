package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.PaymentHistoryDTO;
import com.indraacademy.ias_management.dto.PaymentResponseDTO;
import com.indraacademy.ias_management.service.PaymentService;
import com.indraacademy.ias_management.service.RazorpayService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@CrossOrigin(origins = "http://localhost:4200")
public class PaymentController {

    @Autowired
    private RazorpayService razorpayService;

    @Autowired
    private PaymentService paymentService;

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody Map<String, Object> requestBody) {
        @SuppressWarnings("unchecked")
        Map<String, Object> paymentData = (Map<String, Object>) requestBody.get("paymentData");
        System.out.println("RESQUEST BODY=> " + requestBody);
        int amount = (int) paymentData.get("totalAmount");
        String studentId = (String) paymentData.get("studentId");
        String studentName = (String) paymentData.get("studentName");
        String className = (String) paymentData.get("className");
        String session = (String) paymentData.get("session");
        String month = (String) paymentData.get("monthSelectionString");
        System.out.println(paymentData);
        int busFee = (int) paymentData.get("totalBusFee");
        int tuitionFee = (int) paymentData.get("totalTuitionFee");
        int annualCharges = (int) paymentData.get("totalAnnualCharges");
        int labCharges = (int) paymentData.get("totalLabCharges");
        int ecaProject = (int) paymentData.get("totalEcaProject");
        int examinationFee = (int) paymentData.get("totalExaminationFee");

        Number additionalChargesNum = (Number) paymentData.get("additionalCharges");
        int additionalCharges = additionalChargesNum != null ? additionalChargesNum.intValue() : 0;

        Number lateFeesNum = (Number) paymentData.get("lateFees");
        int lateFees = lateFeesNum != null ? lateFeesNum.intValue() : 0;

        Map<String, Object> order = razorpayService.createOrder(amount, studentId, studentName, className, session, month, busFee, tuitionFee, annualCharges, labCharges, ecaProject, examinationFee, additionalCharges, lateFees);
        return ResponseEntity.ok(order);
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyPayment(@RequestBody Map<String, Object> requestBody) {
        @SuppressWarnings("unchecked")
        Map<String, String> paymentData = (Map<String, String>) requestBody.get("paymentResponse");
        @SuppressWarnings("unchecked")
        Map<String, Object> orderDetails = (Map<String, Object>) requestBody.get("orderDetails");

        Map<String, Object> result = razorpayService.verifyPayment(paymentData, orderDetails);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/history/{studentId}")
    public ResponseEntity<List<PaymentHistoryDTO>> getPaymentHistory(
            @PathVariable String studentId) {
        List<PaymentHistoryDTO> history = paymentService.getPaymentHistory(studentId);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/history/details/{paymentId}")
    public ResponseEntity<PaymentResponseDTO> getPaymentHistoryDetails(@PathVariable String paymentId) {
        PaymentResponseDTO dto = paymentService.getPaymentHistoryDetails(paymentId);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/history/all")
    public ResponseEntity<List<PaymentHistoryDTO>> getAllPaymentHistory() {
        List<PaymentHistoryDTO> allHistory = paymentService.getAllPaymentHistory();
        return ResponseEntity.ok(allHistory);
    }

    @GetMapping("/history/class/{className}")
    public ResponseEntity<List<PaymentHistoryDTO>> getPaymentHistoryByClass(
            @PathVariable String className) {
        List<PaymentHistoryDTO> classHistory = paymentService.getPaymentHistoryByClass(className);
        return ResponseEntity.ok(classHistory);
    }
}