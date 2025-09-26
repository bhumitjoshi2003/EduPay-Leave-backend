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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/student-fees")
@CrossOrigin(origins = "http://localhost:4200")
public class StudentFeesController {

    private static final Logger log = LoggerFactory.getLogger(StudentFeesController.class);

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
        final String resolvedStudentId;

        if(Role.STUDENT.equals(role)){
            resolvedStudentId = authService.getUserIdFromToken(authorizationHeader);
            log.info("Student {} accessing their fees for year: {}", resolvedStudentId, year);
        } else {
            resolvedStudentId = studentId;
            log.info("Admin/Teacher accessing fees for student {} in year: {}", resolvedStudentId, year);
        }

        try {
            List<StudentFees> fees = studentFeesService.getStudentFees(resolvedStudentId, year);
            if (fees.isEmpty()) {
                log.warn("No fees found for student {} in year {}", resolvedStudentId, year);
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(fees);
        } catch (IllegalArgumentException e) {
            log.error("Invalid input for fetching student fees (ID: {}, Year: {}): {}", resolvedStudentId, year, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Unexpected error fetching student fees (ID: {}, Year: {}).", resolvedStudentId, year, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/manual-payment")
    public ResponseEntity<Map<String, String>> recordManualPayment(@RequestBody Map<String, Object> paymentData) {
        String studentId = (String) paymentData.get("studentId");
        log.info("Admin request to record manual payment for student: {}", studentId);

        try {
            String studentName = (String) paymentData.get("studentName");
            String className = (String) paymentData.get("className");
            String session = (String) paymentData.get("session");
            String month = (String) paymentData.get("monthSelectionString");

            int totalAmount = ((Number) paymentData.get("totalAmount")).intValue();
            int amountPaid = ((Number) paymentData.get("amountPaid")).intValue();

            int totalBusFee = ((Number) paymentData.getOrDefault("totalBusFee", 0)).intValue();
            int totalTuitionFee = ((Number) paymentData.getOrDefault("totalTuitionFee", 0)).intValue();
            int totalAnnualCharges = ((Number) paymentData.getOrDefault("totalAnnualCharges", 0)).intValue();
            int totalLabCharges = ((Number) paymentData.getOrDefault("totalLabCharges", 0)).intValue();
            int totalEcaProject = ((Number) paymentData.getOrDefault("totalEcaProject", 0)).intValue();
            int totalExaminationFee = ((Number) paymentData.getOrDefault("totalExaminationFee", 0)).intValue();
            int additionalCharges = ((Number) paymentData.getOrDefault("additionalCharges", 0)).intValue();
            int lateFees = ((Number) paymentData.getOrDefault("lateFees", 0)).intValue();

            Payment payment = new Payment(
                    studentId, studentName, className, session, month, totalAmount,
                    "MANUAL_" + UUID.randomUUID().toString().substring(0, 15),
                    "Manual payment", LocalDateTime.now(), "success",
                    totalBusFee, totalTuitionFee, totalAnnualCharges, totalLabCharges,
                    totalEcaProject, totalExaminationFee, true, amountPaid,
                    additionalCharges, lateFees
            );

            payment.setRazorpaySignature("MANUAL-PAYMENT");

            paymentRepository.save(payment);
            attendanceService.updateChargePaidAfterPayment(studentId, session);

            log.info("Manual payment recorded successfully for student {}. Payment ID: {}", studentId, payment.getPaymentId());
            return new ResponseEntity<>(Map.of("message", "Manual payment recorded successfully", "paymentId", payment.getPaymentId()), HttpStatus.CREATED);

        } catch (ClassCastException | NullPointerException e) {
            log.error("Invalid data format in manual payment request for student {}.", studentId, e);
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid data format in payment request. Check all number fields and required string fields."));
        } catch (Exception e) {
            log.error("Failed to record manual payment for student {}.", studentId, e);
            return new ResponseEntity<>(Map.of("error", "Failed to record manual payment: An internal error occurred."), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PutMapping("/")
    public ResponseEntity<StudentFees> updateStudentFees(@RequestBody StudentFees studentFees) {
        log.info("Request to update student fee record ID: {}", studentFees.getId());
        try {
            return ResponseEntity.ok(studentFeesService.updateStudentFees(studentFees));
        } catch (NoSuchElementException e) {
            log.error("Student fees record not found for update (ID: {}).", studentFees.getId());
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            log.error("Invalid data for student fees update (ID: {}): {}", studentFees.getId(), e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Unexpected error updating student fees record ID: {}.", studentFees.getId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/")
    public ResponseEntity<StudentFees> createStudentFees(@RequestBody StudentFees studentFees) {
        log.info("Request to create student fees record for student: {} month: {}", studentFees.getStudentId(), studentFees.getMonth());
        try {
            return new ResponseEntity<>(studentFeesService.createStudentFees(studentFees), HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            log.error("Failed to create student fees record: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Unexpected error creating student fees record for student: {}.", studentFees.getStudentId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN +  "', '" + Role.STUDENT + "')")
    @GetMapping("/{studentId}/{year}/{month}")
    public ResponseEntity<StudentFees> getStudentFee(@PathVariable String studentId, @PathVariable String year, @PathVariable Integer month, @RequestHeader(name = "Authorization") String authorizationHeader) {

        String role = authService.getRoleFromToken(authorizationHeader);
        final String resolvedStudentId;

        if (role.equals("STUDENT")) {
            resolvedStudentId = authService.getUserIdFromToken(authorizationHeader);
        } else {
            resolvedStudentId = studentId;
        }

        log.info("Request to get student fee for ID: {} Year: {} Month: {}", resolvedStudentId, year, month);

        try {
            Optional<StudentFees> studentFee = studentFeesService.getStudentFee(resolvedStudentId, year, month);
            return studentFee.map(ResponseEntity::ok)
                    .orElseGet(() -> {
                        log.warn("Student fee not found for ID: {} Year: {} Month: {}", resolvedStudentId, year, month);
                        return ResponseEntity.notFound().build();
                    });
        } catch (IllegalArgumentException e) {
            log.error("Invalid input for fetching single student fee (ID: {} Year: {} Month: {}): {}", resolvedStudentId, year, month, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Unexpected error fetching single student fee (ID: {} Year: {} Month: {}).", resolvedStudentId, year, month, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/sessions/{studentId}")
    public ResponseEntity<List<String>> getDistinctYearsByStudentId(@PathVariable String studentId) {
        log.info("Request to get distinct academic years for student: {}", studentId);
        try {
            List<String> sessions = studentFeesService.getDistinctYearsByStudentId(studentId);
            return ResponseEntity.ok(sessions);
        } catch (Exception e) {
            log.error("Error fetching distinct years for student: {}.", studentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}