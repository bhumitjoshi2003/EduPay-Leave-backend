package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.OverdueStudentDto;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.service.AttendanceService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.FeeReminderService;
import com.indraacademy.ias_management.service.StudentFeesService;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/student-fees")
@CrossOrigin(origins = "http://localhost:4200")
public class StudentFeesController {

    private static final Logger log = LoggerFactory.getLogger(StudentFeesController.class);

    @Autowired private StudentFeesService studentFeesService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private AttendanceService attendanceService;
    @Autowired private AuthService authService;
    @Autowired private FeeReminderService feeReminderService;

    @PreAuthorize("hasAnyRole('" + Role.ADMIN +  "', '" + Role.STUDENT + "')")
    @GetMapping("/{studentId}/{year}")
    public ResponseEntity<List<StudentFees>> getStudentFees(
            @PathVariable String studentId,
            @PathVariable String year) {

        String role = authService.getRole();
        final String resolvedStudentId;

        if(Role.STUDENT.equals(role)){
            resolvedStudentId = authService.getUserId();
            log.info("Student {} accessing their fees for year: {}", resolvedStudentId, year);
        } else {
            resolvedStudentId = studentId;
            log.info("Admin/Teacher accessing fees for student {} in year: {}", resolvedStudentId, year);
        }

        List<StudentFees> fees = studentFeesService.getStudentFees(resolvedStudentId, year);
        if (fees.isEmpty()) {
            log.warn("No fees found for student {} in year {}", resolvedStudentId, year);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(fees);
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/manual-payment")
    public ResponseEntity<Map<String, String>> recordManualPayment(@RequestBody Map<String, Object> paymentData, HttpServletRequest request) {
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
            attendanceService.updateChargePaidAfterPayment(studentId, session, request);

            log.info("Manual payment recorded successfully for student {}. Payment ID: {}", studentId, payment.getPaymentId());
            return new ResponseEntity<>(Map.of("message", "Manual payment recorded successfully", "paymentId", payment.getPaymentId()), HttpStatus.CREATED);

        } catch (ClassCastException | NullPointerException e) {
            log.error("Invalid data format in manual payment request for student {}.", studentId, e);
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid data format in payment request. Check all number fields and required string fields."));
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PutMapping("/")
    public ResponseEntity<StudentFees> updateStudentFees(@RequestBody StudentFees studentFees) {
        log.info("Request to update student fee record ID: {}", studentFees.getId());
        return ResponseEntity.ok(studentFeesService.updateStudentFees(studentFees));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/")
    public ResponseEntity<StudentFees> createStudentFees(@RequestBody StudentFees studentFees) {
        log.info("Request to create student fees record for student: {} month: {}", studentFees.getStudentId(), studentFees.getMonth());
        return new ResponseEntity<>(studentFeesService.createStudentFees(studentFees), HttpStatus.CREATED);
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN +  "', '" + Role.STUDENT + "')")
    @GetMapping("/{studentId}/{year}/{month}")
    public ResponseEntity<StudentFees> getStudentFee(@PathVariable String studentId, @PathVariable String year, @PathVariable Integer month) {

        String role = authService.getRole();
        final String resolvedStudentId;

        if (Role.STUDENT.equals(role)) {
            resolvedStudentId = authService.getUserId();
        } else {
            resolvedStudentId = studentId;
        }

        log.info("Request to get student fee for ID: {} Year: {} Month: {}", resolvedStudentId, year, month);

        Optional<StudentFees> studentFee = studentFeesService.getStudentFee(resolvedStudentId, year, month);
        return studentFee.map(ResponseEntity::ok)
                .orElseGet(() -> {
                    log.warn("Student fee not found for ID: {} Year: {} Month: {}", resolvedStudentId, year, month);
                    return ResponseEntity.notFound().build();
                });
    }

    @GetMapping("/sessions/{studentId}")
    public ResponseEntity<List<String>> getDistinctYearsByStudentId(@PathVariable String studentId) {
        log.info("Request to get distinct academic years for student: {}", studentId);
        List<String> sessions = studentFeesService.getDistinctYearsByStudentId(studentId);
        return ResponseEntity.ok(sessions);
    }

    // ─── Overdue & Reminders ──────────────────────────────────────────────────

    /**
     * GET /api/student-fees/overdue?session=2025-2026&className=optional
     * Returns all active students with unpaid, past-due fee months for the given session.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @GetMapping("/overdue")
    public ResponseEntity<?> getOverdueStudents(
            @RequestParam String session,
            @RequestParam(required = false) String className) {
        log.info("Overdue fees request: session={}, className={}", session, className);
        List<OverdueStudentDto> result = feeReminderService.getOverdueStudents(session, className);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/student-fees/reminders/send
     * Body: { studentId, session }
     * Sends a fee reminder email to a single student and logs the action.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PostMapping("/reminders/send")
    public ResponseEntity<?> sendReminder(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String studentId = body.get("studentId");
        String session   = body.get("session");
        log.info("Manual reminder request: studentId={}, session={}", studentId, session);

        if (studentId == null || session == null) {
            return ResponseEntity.badRequest().body("studentId and session are required.");
        }
        boolean sent = feeReminderService.sendReminder(studentId, session, request);
        if (sent) {
            return ResponseEntity.ok(Map.of("message", "Reminder sent successfully."));
        } else {
            return ResponseEntity.ok(Map.of("message", "Student has no email configured; reminder not sent."));
        }
    }

    /**
     * POST /api/student-fees/reminders/send-bulk
     * Body: { studentIds: [...], session }
     * Sends reminders to all listed students. Returns { sent: N }.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PostMapping("/reminders/send-bulk")
    public ResponseEntity<?> sendBulkReminders(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        List<String> studentIds = (List<String>) body.get("studentIds");
        String session = (String) body.get("session");
        log.info("Bulk reminder request: {} students, session={}", studentIds != null ? studentIds.size() : 0, session);

        if (studentIds == null || studentIds.isEmpty() || session == null) {
            return ResponseEntity.badRequest().body("studentIds (non-empty list) and session are required.");
        }
        int sent = feeReminderService.sendBulkReminders(studentIds, session, request);
        return ResponseEntity.ok(Map.of("sent", sent));
    }
}