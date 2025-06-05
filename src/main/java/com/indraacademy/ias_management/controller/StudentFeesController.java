package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.service.AttendanceService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.StudentFeesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/student-fees")
@CrossOrigin(origins = "http://localhost:4200")
public class StudentFeesController {

    @Autowired private StudentFeesService studentFeesService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private AttendanceService attendanceService;
    @Autowired private AuthService authService;

    @PreAuthorize("hasAnyRole('" + Role.ADMIN +  "', '" + Role.STUDENT + "')")
    @GetMapping("/{studentId}/{year}")
    public ResponseEntity<List<StudentFees>> getStudentFees(
            @PathVariable String studentId,
            @PathVariable String year, @RequestHeader(name = "Authorization") String authorizationHeader) {
        String role = authService.getRoleFromToken(authorizationHeader);
        if(role.equals("STUDENT")){
            studentId = authService.getUserIdFromToken(authorizationHeader);
        }
        return ResponseEntity.ok(studentFeesService.getStudentFees(studentId, year));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/manual-payment")
    public ResponseEntity<Map<String, String>> recordManualPayment(@RequestBody Map<String, Object> paymentData) {
        try {
            String studentId = (String) paymentData.get("studentId");
            String studentName = (String) paymentData.get("studentName");
            String className = (String) paymentData.get("className");
            String session = (String) paymentData.get("session");
            String month = (String) paymentData.get("monthSelectionString");
            Number totalAmountNumber = (Number) paymentData.get("totalAmount");
            int totalAmount = totalAmountNumber.intValue();
            Number amountPaidNumber = (Number) paymentData.get("amountPaid");
            int amountPaid = amountPaidNumber.intValue();
            Number totalBusFeeNumber = (Number) paymentData.get("totalBusFee");
            int totalBusFee = totalBusFeeNumber != null ? totalBusFeeNumber.intValue() : 0;
            Number totalTuitionFeeNumber = (Number) paymentData.get("totalTuitionFee");
            int totalTuitionFee = totalTuitionFeeNumber != null ? totalTuitionFeeNumber.intValue() : 0;
            Number totalAnnualChargesNumber = (Number) paymentData.get("totalAnnualCharges");
            int totalAnnualCharges = totalAnnualChargesNumber != null ? totalAnnualChargesNumber.intValue() : 0;
            Number totalLabChargesNumber = (Number) paymentData.get("totalLabCharges");
            int totalLabCharges = totalLabChargesNumber != null ? totalLabChargesNumber.intValue() : 0;
            Number totalEcaProjectNumber = (Number) paymentData.get("totalEcaProject");
            int totalEcaProject = totalEcaProjectNumber != null ? totalEcaProjectNumber.intValue() : 0;
            Number totalExaminationFeeNumber = (Number) paymentData.get("totalExaminationFee");
            int totalExaminationFee = totalExaminationFeeNumber != null ? totalExaminationFeeNumber.intValue() : 0;
            Number additionalChargesNumber = (Number) paymentData.get("additionalCharges");
            int additionalCharges = additionalChargesNumber != null ? additionalChargesNumber.intValue() : 0;
            Number lateFeesNumber = (Number) paymentData.get("lateFees");
            int lateFees = lateFeesNumber != null ? lateFeesNumber.intValue() : 0;


            Payment payment = new Payment(
                    studentId,
                    studentName,
                    className,
                    session,
                    month,
                    totalAmount,
                    "MANUAL_" + UUID.randomUUID().toString().substring(0, 15),
                    "Manual payment",
                    LocalDateTime.now(),
                    "success",
                    totalBusFee,
                    totalTuitionFee,
                    totalAnnualCharges,
                    totalLabCharges,
                    totalEcaProject,
                    totalExaminationFee,
                    true,
                    amountPaid,
                    additionalCharges,
                    lateFees
            );

            payment.setRazorpaySignature("MANUAL-PAYMENT");

            paymentRepository.save(payment);

            attendanceService.updateChargePaidAfterPayment(studentId, session);

            return new ResponseEntity<>(Map.of("message", "Manual payment recorded successfully", "paymentId", payment.getPaymentId()), HttpStatus.CREATED);

        } catch (Exception e) {
            e.printStackTrace();
            return new ResponseEntity<>(Map.of("error", "Failed to record manual payment: " + e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PutMapping("/")
    public ResponseEntity<StudentFees> updateStudentFees(@RequestBody StudentFees studentFees) {
        return ResponseEntity.ok(studentFeesService.updateStudentFees(studentFees));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/")
    public ResponseEntity<StudentFees> createStudentFees(@RequestBody StudentFees studentFees) {
        return ResponseEntity.ok(studentFeesService.createStudentFees(studentFees));
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN +  "', '" + Role.STUDENT + "')")
    @GetMapping("/{studentId}/{year}/{month}")
    public ResponseEntity<StudentFees> getStudentFee(@PathVariable String studentId, @PathVariable String year, @PathVariable Integer month, @RequestHeader(name = "Authorization") String authorizationHeader) {
        String role = authService.getRoleFromToken(authorizationHeader);
        if (role.equals("STUDENT")) {
            studentId = authService.getUserIdFromToken(authorizationHeader);
        }
        Optional<StudentFees> studentFee = studentFeesService.getStudentFee(studentId, year, month);
        return studentFee.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/sessions/{studentId}")
    public ResponseEntity<List<String>> getDistinctYearsByStudentId(@PathVariable String studentId) {
        List<String> sessions = studentFeesService.getDistinctYearsByStudentId(studentId);
        return ResponseEntity.ok(sessions);
    }
}
