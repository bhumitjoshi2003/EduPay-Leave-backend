package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.PaymentHistoryDTO;
import com.indraacademy.ias_management.dto.PaymentResponseDTO;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.PaymentService;
import com.indraacademy.ias_management.service.RazorpayService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@CrossOrigin(origins = "http://localhost:4200")
public class PaymentController {

    @Autowired private RazorpayService razorpayService;
    @Autowired private PaymentService paymentService;
    @Autowired private AuthService authService;

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

    @GetMapping("/history/students")
    public ResponseEntity<Page<Payment>> getPaymentHistory(
            @RequestParam(required = false) String className,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false)  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDate,
            Pageable pageable
    ) {
        return ResponseEntity.ok(
                paymentService.gePaymentHistoryFiltered(className, studentId, paymentDate, pageable)
        );
    }

    @GetMapping("/history/student/{studentId}")
    public ResponseEntity<Page<Payment>> getPaymentHistoryOfStudent(
            @PathVariable String studentId, Pageable pageable,
            @RequestHeader(name = "Authorization") String authorizationHeader){
        studentId = authService.getUserIdFromToken(authorizationHeader);
        return ResponseEntity.ok(
                paymentService.getPaymentHistoryByStudentId(studentId, pageable));
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT +  "')")
    @GetMapping("/history/details/{paymentId}")
    public ResponseEntity<PaymentResponseDTO> getPaymentHistoryDetails(@PathVariable String paymentId) {
        PaymentResponseDTO dto = paymentService.getPaymentHistoryDetails(paymentId);
        System.out.println("DTO => " + dto);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/history/receipt/{paymentId}")
    public ResponseEntity<byte[]> downloadPaymentReceipt(@PathVariable String paymentId) {
        byte[] pdfBytes = paymentService.generatePaymentReceiptPdf(paymentId);

        if (pdfBytes == null || pdfBytes.length == 0) {
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "receipt_" + paymentId + ".pdf");

        return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
    }
}