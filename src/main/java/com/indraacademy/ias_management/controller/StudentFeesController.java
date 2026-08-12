package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.CheckoutQuoteDto;
import com.indraacademy.ias_management.dto.ManualPaymentRequest;
import com.indraacademy.ias_management.dto.MonthFeeBreakdownDto;
import com.indraacademy.ias_management.dto.RecalculationApplyRequestDto;
import com.indraacademy.ias_management.dto.RecalculationEntryDto;
import com.indraacademy.ias_management.dto.RecalculationPreviewRequestDto;
import com.indraacademy.ias_management.service.StudentFeesRecalculationService;
import com.indraacademy.ias_management.dto.OverdueStudentDto;
import com.indraacademy.ias_management.dto.StudentFeesAdminUpdateRequest;
import com.indraacademy.ias_management.dto.StudentFeesCreateRequest;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.StudentFees;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/student-fees")
public class StudentFeesController {

    private static final Logger log = LoggerFactory.getLogger(StudentFeesController.class);

    @Autowired private StudentFeesService studentFeesService;
    @Autowired private AttendanceService attendanceService;
    @Autowired private AuthService authService;
    @Autowired private FeeReminderService feeReminderService;
    @Autowired private StudentFeesRecalculationService recalculationService;

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

    /**
     * POST /api/student-fees/manual-payment
     * Admin-recorded manual (cash/cheque/UPI/bank-transfer) payment. The admin supplies only
     * what they actually observed — student, months, amount received, mode, reference — the
     * amount actually owed and the resulting StudentFees.paid state are computed and applied
     * server-side by StudentFeesService.recordManualPayment, reusing the same snapshot-first
     * resolution Razorpay/reminders use (FeeCalculationService.resolveSchoolFeeDue). A month
     * whose amount can't be resolved, an amount that doesn't cover the computed total, or a
     * reused reference number are all rejected with 400/409 — nothing is partially recorded.
     */
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/manual-payment")
    public ResponseEntity<Map<String, String>> recordManualPayment(@RequestBody ManualPaymentRequest paymentRequest, HttpServletRequest request) {
        String studentId = paymentRequest != null ? paymentRequest.getStudentId() : null;
        log.info("Admin request to record manual payment for student: {}", studentId);

        try {
            Payment payment = studentFeesService.recordManualPayment(paymentRequest, request.getRemoteAddr());
            attendanceService.updateChargePaidAfterPayment(payment.getStudentId(), payment.getSession(), request);

            log.info("Manual payment recorded successfully for student {}. Payment ID: {}", studentId, payment.getPaymentId());
            return new ResponseEntity<>(Map.of("message", "Manual payment recorded successfully", "paymentId", payment.getPaymentId()), HttpStatus.CREATED);

        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Rejected manual payment request for student {}: {}", studentId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * PUT /api/student-fees/ — deliberately restricted to StudentFeesAdminUpdateRequest's
     * non-financial fields (className/classId/takesBus/distance). paid/amountPaid/
     * manuallyPaid/manualPaymentReceived/snapshot fields cannot be set through this endpoint
     * regardless of what the caller sends — see StudentFeesService.updateStudentFees.
     */
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PutMapping("/")
    public ResponseEntity<?> updateStudentFees(@RequestBody StudentFeesAdminUpdateRequest request) {
        log.info("Request to update student fee record ID: {}", request != null ? request.getId() : null);
        try {
            return ResponseEntity.ok(studentFeesService.updateStudentFees(request));
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/student-fees/ — ad-hoc single-row creation, restricted to
     * StudentFeesCreateRequest's identity/non-financial fields. The row's financial
     * snapshot is always computed server-side; it is never accepted from the client, and the
     * row always starts unpaid — see StudentFeesService.createStudentFees.
     */
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/")
    public ResponseEntity<?> createStudentFees(@RequestBody StudentFeesCreateRequest request) {
        log.info("Request to create student fees record for student: {} month: {}",
                request != null ? request.getStudentId() : null, request != null ? request.getMonth() : null);
        try {
            return new ResponseEntity<>(studentFeesService.createStudentFees(request), HttpStatus.CREATED);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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

    /**
     * GET /api/student-fees/{studentId}/{year}/{month}/breakdown
     * Backend-authoritative per-fee-head breakdown for a single month, replacing the
     * frontend's former self-constructed "School Fee" + "Discount" display (which
     * double-subtracted discountAmount from the already-net baseAmountDue). Never
     * fabricates a breakdown for historical rows with no StudentFeesLineItem data — see
     * MonthFeeBreakdownDto's javadoc.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "')")
    @GetMapping("/{studentId}/{year}/{month}/breakdown")
    public ResponseEntity<MonthFeeBreakdownDto> getMonthFeeBreakdown(
            @PathVariable String studentId,
            @PathVariable String year,
            @PathVariable Integer month) {

        String role = authService.getRole();
        String resolvedStudentId = Role.STUDENT.equals(role) ? authService.getUserId() : studentId;

        log.info("Request to get month fee breakdown for ID: {} Year: {} Month: {}", resolvedStudentId, year, month);

        Optional<MonthFeeBreakdownDto> breakdown = studentFeesService.getMonthFeeBreakdown(resolvedStudentId, year, month);
        return breakdown.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/sessions/{studentId}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "')")
    public ResponseEntity<List<String>> getDistinctYearsByStudentId(@PathVariable String studentId) {
        log.info("Request to get distinct academic years for student: {}", studentId);
        List<String> sessions = studentFeesService.getDistinctYearsByStudentId(studentId);
        return ResponseEntity.ok(sessions);
    }

    /**
     * GET /api/student-fees/{studentId}/checkout-quote?session=2025-2026&months=6,7,8
     * Backend-authoritative checkout amount for a set of selected academic months — school
     * fee due (from each month's stored StudentFees snapshot) + late fee + platform fee =
     * totalAmount. Angular must display these values as-is, never recompute them itself.
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "')")
    @GetMapping("/{studentId}/checkout-quote")
    public ResponseEntity<?> getCheckoutQuote(
            @PathVariable String studentId,
            @RequestParam String session,
            @RequestParam String months) {

        String role = authService.getRole();
        String resolvedStudentId = Role.STUDENT.equals(role) ? authService.getUserId() : studentId;

        List<Integer> monthList;
        try {
            monthList = java.util.Arrays.stream(months.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .collect(java.util.stream.Collectors.toList());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "months must be a comma-separated list of integers."));
        }
        if (monthList.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "months must not be empty."));
        }

        log.info("Checkout quote request: student={} session={} months={}", resolvedStudentId, session, monthList);
        CheckoutQuoteDto quote = studentFeesService.computeCheckoutQuote(resolvedStudentId, session, monthList);
        return ResponseEntity.ok(quote);
    }

    // ─── Recalculation (Phase 5A) ──────────────────────────────────────────────

    /**
     * POST /api/student-fees/recalculate/preview
     * Admin-only. Computes what each requested month's snapshot WOULD become under the
     * current fee configuration, without writing anything — for ineligible/not-found months
     * (already paid, has allocation/refund history, no row, no valid config) this reports why
     * rather than silently omitting them. See StudentFeesRecalculationService.preview.
     */
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/recalculate/preview")
    public ResponseEntity<?> previewRecalculation(@RequestBody RecalculationPreviewRequestDto request) {
        if (request == null || request.getStudentId() == null || request.getStudentId().isBlank()
                || request.getSession() == null || request.getSession().isBlank()
                || request.getMonths() == null || request.getMonths().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "studentId, session, and a non-empty months list are required."));
        }
        log.info("Admin recalculation preview requested for student={} session={} months={}",
                request.getStudentId(), request.getSession(), request.getMonths());
        List<RecalculationEntryDto> results = recalculationService.preview(request.getStudentId(), request.getSession(), request.getMonths());
        return ResponseEntity.ok(results);
    }

    /**
     * POST /api/student-fees/recalculate/apply
     * Admin-only. Requires a non-blank reason. Each requested month is recalculated in its
     * own transaction (StudentFeesRecalculationService.recalculateOne is @Transactional; this
     * loop calls it per month through the injected bean so each gets its own real
     * transaction boundary) — one month's rejection or failure never blocks or rolls back
     * another month's successful recalculation in the same request. Every value here is
     * recomputed server-side; nothing monetary is ever accepted from the client.
     */
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/recalculate/apply")
    public ResponseEntity<?> applyRecalculation(@RequestBody RecalculationApplyRequestDto request, HttpServletRequest httpRequest) {
        if (request == null || request.getStudentId() == null || request.getStudentId().isBlank()
                || request.getSession() == null || request.getSession().isBlank()
                || request.getMonths() == null || request.getMonths().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "studentId, session, and a non-empty months list are required."));
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "reason is required."));
        }
        log.info("Admin recalculation apply requested for student={} session={} months={} reason={}",
                request.getStudentId(), request.getSession(), request.getMonths(), request.getReason());

        String ip = httpRequest.getRemoteAddr();
        List<RecalculationEntryDto> results = new ArrayList<>();
        for (Integer month : request.getMonths()) {
            results.add(recalculationService.recalculateOne(request.getStudentId(), request.getSession(), month, request.getReason(), ip));
        }
        return ResponseEntity.ok(results);
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