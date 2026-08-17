package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.CheckoutQuoteDto;
import com.indraacademy.ias_management.dto.OverdueStudentDto;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FeeReminderService {

    private static final Logger log = LoggerFactory.getLogger(FeeReminderService.class);

    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private FeeCalculationService feeCalculationService;
    @Autowired private StudentFeesService studentFeesService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private EmailService emailService;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;

    // ─── Scheduled reminder ───────────────────────────────────────────────────

    @Scheduled(cron = "0 0 6 28 * *", zone = "Asia/Kolkata")
    public void sendMonthlyFeeReminders() {
        LocalDate today = LocalDate.now();
        log.info("Starting monthly fee reminder run for date: {}", today);

        // Process per school so each school's academic calendar is respected
        List<School> activeSchools = schoolRepository.findAll().stream()
                .filter(School::isActive)
                .collect(java.util.stream.Collectors.toList());

        for (School school : activeSchools) {
            try {
                int startMonth = school.getAcademicYearStartMonth();
                String academicYear = getAcademicYear(today, startMonth);
                int academicMonth = getAcademicMonth(today.getMonthValue(), startMonth);
                log.info("School {} — academicYear={}, academicMonth={}", school.getId(), academicYear, academicMonth);

                List<StudentFees> unpaidFees = studentFeesRepository
                        .findAllUnpaidBySchoolIdAndYearAndMonth(school.getId(), academicYear, academicMonth);

                for (StudentFees fee : unpaidFees) {
                    try {
                        processScheduledReminder(fee, startMonth);
                    } catch (Exception e) {
                        log.error("Error processing reminder for student {}: {}", fee.getStudentId(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("Error processing school {} in fee reminder run: {}", school.getId(), e.getMessage());
            }
        }
    }

    private void processScheduledReminder(StudentFees fee, int startMonth) {
        studentRepository.findByStudentIdAndSchoolId(fee.getStudentId(), fee.getSchoolId()).ifPresentOrElse(student -> {
            String email = student.getEmail();
            if (email == null || email.isEmpty()) {
                log.warn("Skipping: Student {} has no email address.", student.getStudentId());
                return;
            }
            School school = schoolRepository.findById(student.getSchoolId() != null ? student.getSchoolId() : -1L)
                    .orElse(null);
            String schoolName = school != null ? school.getName() : "School";
            String monthName = getMonthName(fee.getMonth(), startMonth);
            String subject = "Fee Payment Reminder – " + monthName + " (" + fee.getYear() + ")";
            String studentName = student.getName() != null ? student.getName() : "Student";
            String htmlBody = buildFeeReminderHtml(studentName, monthName, fee.getYear(), schoolName);
            log.info("Triggering scheduled reminder email to: {}", email);
            emailService.sendHtmlEmail(email, subject, htmlBody);
        }, () -> log.error("Database Error: Student ID {} not found in Student table.", fee.getStudentId()));
    }

    // ─── Overdue query ────────────────────────────────────────────────────────

    /**
     * Returns overdue (unpaid, past-due-date) fee summaries per active student.
     * A month is overdue when its 1st calendar day is strictly before today.
     */
    @Transactional(readOnly = true)
    public List<OverdueStudentDto> getOverdueStudents(String session, String className) {
        LocalDate today = LocalDate.now();

        int[] years = parseSession(session);
        int startYear = years[0];
        int endYear   = years[1];

        Long schoolId = securityUtil.getSchoolId();
        int startMonth = schoolRepository.findById(schoolId)
                .map(School::getAcademicYearStartMonth).orElse(4);

        // Fetch all unpaid fee records for the session (optionally filtered by class)
        List<StudentFees> unpaid = (className != null && !className.isBlank())
                ? studentFeesRepository.findAllUnpaidBySchoolIdAndSessionAndClassName(schoolId, session, className)
                : studentFeesRepository.findAllUnpaidBySchoolIdAndSession(schoolId, session);

        // Filter to months that have already started (1st day <= start of current month)
        LocalDate currentMonthStart = today.withDayOfMonth(1);
        List<StudentFees> overdue = unpaid.stream()
                .filter(sf -> !academicMonthStart(sf.getMonth(), startYear, endYear, startMonth).isAfter(currentMonthStart))
                .collect(Collectors.toList());

        // Group by studentId
        Map<String, List<StudentFees>> byStudent = overdue.stream()
                .collect(Collectors.groupingBy(StudentFees::getStudentId));

        List<OverdueStudentDto> result = new ArrayList<>();

        for (Map.Entry<String, List<StudentFees>> entry : byStudent.entrySet()) {
            String studentId = entry.getKey();
            List<StudentFees> fees = entry.getValue();

            Optional<Student> studentOpt = studentRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId());
            if (studentOpt.isEmpty()) continue;
            Student student = studentOpt.get();

            // Only active students
            if (student.getStatus() != StudentStatus.ACTIVE) continue;

            // Sort fees by month ascending so unpaidMonths and daysOverdue are deterministic
            fees.sort(Comparator.comparingInt(StudentFees::getMonth));

            List<String> unpaidMonthNames = fees.stream()
                    .map(sf -> getMonthName(sf.getMonth(), startMonth))
                    .collect(Collectors.toList());

            // totalDue: delegates to StudentFeesService.computeCheckoutQuote — the exact same
            // backend-authoritative calculation the Fees UI's checkout screen uses — instead of
            // separately re-summing each month's snapshot here. That prior approach summed only
            // resolveSchoolFeeDue (baseAmountDue + busFeeDue − discountAmount) and silently
            // omitted the late fee that accrues on every overdue month, so this tool understated
            // what a parent actually owes vs. what the Fees page shows for the same months (e.g.
            // ₹17,800 reported here vs. ₹18,160 owed once the accrued late fee is included —
            // confirmed live against real StudentFees/checkout-quote data). platformFee is
            // deliberately excluded from totalDue: per CheckoutQuoteDto's own contract it is a
            // payment-time-only convenience charge, never part of the underlying school debt, so
            // including it here would overstate what's owed the same way omitting the late fee
            // understated it. unresolvedMonths again means the total is genuinely unknown, not
            // partially summed — same "null, never a silently-short ₹ figure" rule as before.
            String cls = student.getClassName();
            List<Integer> monthNumbers = fees.stream().map(StudentFees::getMonth).collect(Collectors.toList());
            CheckoutQuoteDto quote = studentFeesService.computeCheckoutQuote(studentId, session, monthNumbers);
            Double totalDue = quote.getUnresolvedMonths().isEmpty()
                    ? quote.getSchoolFeeDue().add(quote.getLateFee()).doubleValue()
                    : null;

            // Last payment date
            String lastPaymentDate = paymentRepository
                    .findLatestPaymentDateByStudentIdAndSchoolIdAndSession(studentId, schoolId, session)
                    .map(dt -> dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                    .orElse(null);

            // daysOverdue = today − 1st of the oldest overdue month
            LocalDate oldestMonthStart = academicMonthStart(fees.get(0).getMonth(), startYear, endYear, startMonth);
            int daysOverdue = (int) ChronoUnit.DAYS.between(oldestMonthStart, today);

            OverdueStudentDto dto = new OverdueStudentDto();
            dto.setStudentId(studentId);
            dto.setStudentName(student.getName());
            dto.setClassName(cls);
            dto.setParentPhone(student.getPhoneNumber());
            dto.setParentEmail(student.getEmail());
            dto.setUnpaidMonths(unpaidMonthNames);
            dto.setTotalDue(totalDue);
            dto.setLastPaymentDate(lastPaymentDate);
            dto.setDaysOverdue(daysOverdue);

            result.add(dto);
        }

        // Sort by daysOverdue descending (most overdue first)
        result.sort(Comparator.comparingInt(OverdueStudentDto::getDaysOverdue).reversed());
        return result;
    }

    // ─── Manual reminder sending ──────────────────────────────────────────────

    /**
     * Sends a fee reminder email to a single student and logs one audit entry.
     * Returns true if the email was sent (student has an email), false otherwise.
     */
    public boolean sendReminder(String studentId, String session, HttpServletRequest request) {
        String monthList = sendReminderEmail(studentId, session);
        if (monthList == null) return false;

        auditService.log(
                securityUtil.getUsername(),
                securityUtil.getRole(),
                "SEND_FEE_REMINDER",
                "StudentFees",
                studentId,
                null,
                "Reminder sent for session " + session + "; months: " + monthList,
                request.getRemoteAddr()
        );
        return true;
    }

    /**
     * Sends reminders to all students in the list and logs a single summary audit entry.
     * Returns the count of successful sends.
     */
    public int sendBulkReminders(List<String> studentIds, String session, HttpServletRequest request) {
        List<String> reached = new ArrayList<>();
        int sent = 0;
        for (String studentId : studentIds) {
            try {
                String monthList = sendReminderEmail(studentId, session);
                if (monthList != null) {
                    reached.add(studentId);
                    sent++;
                }
            } catch (Exception e) {
                log.error("Failed to send reminder for student {}: {}", studentId, e.getMessage());
            }
        }

        if (!reached.isEmpty()) {
            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "SEND_FEE_REMINDER_BULK",
                    "StudentFees",
                    null,
                    null,
                    "Bulk reminder sent for session " + session + "; " + sent + " student(s): " + String.join(", ", reached),
                    request.getRemoteAddr()
            );
        }
        return sent;
    }

    /**
     * Sends reminders to all students in the list and returns a per-student outcome map
     * ("sent" | "failed"), unlike {@link #sendBulkReminders} which only returns a count.
     * Used by the AI-copilot workflow's dispatch step (AiWorkflowController), which needs
     * to report partial failures clearly rather than a single opaque number. Does not do
     * its own audit logging — the caller (which also owns the ai_fee_reminder_batch
     * idempotency row) logs one summary entry for the whole batch.
     *
     * Uses {@link #sendReminderEmailSync}, NOT {@link #sendReminderEmail} — the async
     * version can only report "we handed it to the mail system," never a real outcome (see
     * that method's Javadoc). A caller promising per-student outcomes needs the real thing.
     */
    public Map<String, String> sendReminderEmailsWithOutcomes(List<String> studentIds, String session) {
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (String studentId : studentIds) {
            try {
                boolean sent = sendReminderEmailSync(studentId, session);
                outcomes.put(studentId, sent ? "sent" : "failed");
            } catch (Exception e) {
                log.error("Failed to send workflow reminder for student {}: {}", studentId, e.getMessage());
                outcomes.put(studentId, "failed");
            }
        }
        return outcomes;
    }

    private record ReminderEmailContent(String email, String subject, String htmlBody, String monthList) {}

    /** Shared by sendReminderEmail and sendReminderEmailSync — builds the email content,
     * doesn't send it. Returns null if the student has no email on file. */
    private ReminderEmailContent buildReminderEmailContent(String studentId, String session) {
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId())
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        String email = student.getEmail();
        if (email == null || email.isBlank()) {
            return null;
        }

        int[] years = parseSession(session);
        LocalDate currentMonthStart = LocalDate.now().withDayOfMonth(1);

        int reminderStartMonth = schoolRepository.findById(securityUtil.getSchoolId())
                .map(School::getAcademicYearStartMonth).orElse(4);

        List<StudentFees> overdueMonths = studentFeesRepository.findAllUnpaidBySchoolIdAndSessionAndClassName(securityUtil.getSchoolId(), session, student.getClassName())
                .stream()
                .filter(sf -> sf.getStudentId().equals(studentId))
                .filter(sf -> !academicMonthStart(sf.getMonth(), years[0], years[1], reminderStartMonth).isAfter(currentMonthStart))
                .sorted(Comparator.comparingInt(StudentFees::getMonth))
                .collect(Collectors.toList());
        String monthList = overdueMonths.isEmpty()
                ? "upcoming months"
                : overdueMonths.stream().map(sf -> getMonthName(sf.getMonth(), reminderStartMonth)).collect(Collectors.joining(", "));

        String subject = "Fee Payment Reminder – " + session;
        String studentName = student.getName() != null ? student.getName() : "Parent/Guardian";
        String schoolName = schoolRepository.findById(securityUtil.getSchoolId())
                .map(School::getName).orElse("School");
        String htmlBody = buildFeeReminderHtml(studentName, monthList, session, schoolName);

        return new ReminderEmailContent(email, subject, htmlBody, monthList);
    }

    /**
     * Sends the reminder email for one student via the existing fire-and-forget
     * {@link EmailService#sendHtmlEmail}. Returns the month list string as soon as the
     * student is confirmed to have an email on file — NOT proof of actual delivery.
     * {@code @Async void sendHtmlEmail} returns before the SMTP call even happens and
     * catches every exception internally, so nothing here can ever detect a real send
     * failure. Fine for the interactive single/bulk-send endpoints, where the tradeoff is
     * a fast HTTP response and a human already watching the result. NOT used by the
     * AI-workflow dispatch path — see sendReminderEmailSync for why.
     */
    private String sendReminderEmail(String studentId, String session) {
        ReminderEmailContent content = buildReminderEmailContent(studentId, session);
        if (content == null) {
            log.warn("Cannot send reminder: student {} has no email.", studentId);
            return null;
        }
        emailService.sendHtmlEmail(content.email(), content.subject(), content.htmlBody());
        log.info("Fee reminder sent to student {} ({})", studentId, content.email());
        return content.monthList();
    }

    /**
     * Synchronous variant used only by {@link #sendReminderEmailsWithOutcomes} (the
     * AI-workflow dispatch path). Blocks on the real SMTP round-trip so it can report a
     * genuine per-student true/false outcome — the async path structurally cannot do this
     * (see sendReminderEmail's Javadoc). Deliberately not used by the interactive
     * single/bulk-send endpoints, which keep their existing fast, fire-and-forget behavior.
     */
    private boolean sendReminderEmailSync(String studentId, String session) {
        ReminderEmailContent content = buildReminderEmailContent(studentId, session);
        if (content == null) {
            log.warn("Cannot send reminder (sync): student {} has no email.", studentId);
            return false;
        }
        boolean sent = emailService.sendHtmlEmailSync(content.email(), content.subject(), content.htmlBody());
        if (sent) {
            log.info("Fee reminder sent (sync) to student {} ({})", studentId, content.email());
        }
        return sent;
    }

    // ─── Email template ───────────────────────────────────────────────────────

    private String buildFeeReminderHtml(String studentName, String monthList, String session, String schoolName) {
        String safeSchool = (schoolName != null && !schoolName.isBlank()) ? schoolName : "School";
        int year = LocalDate.now().getYear();
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Fee Payment Reminder</title>
            </head>
            <body style="margin:0;padding:0;background-color:#f4f6f9;font-family:Arial,Helvetica,sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f6f9;padding:32px 16px;">
                <tr>
                  <td align="center">
                    <table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;">

                      <!-- ── Header ── -->
                      <tr>
                        <td align="center" style="background-color:#991b1b;border-radius:16px 16px 0 0;padding:36px 40px 28px;">
                          <p style="margin:0 0 12px;font-size:48px;line-height:1;">&#127891;</p>
                          <h1 style="margin:0;color:#ffffff;font-size:26px;font-weight:800;letter-spacing:-0.5px;">%s</h1>
                        </td>
                      </tr>

                      <!-- ── Session band ── -->
                      <tr>
                        <td align="center" style="background-color:#dc2626;padding:10px 40px;">
                          <p style="margin:0;color:#ffffff;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;">
                            Fee Payment Reminder &mdash; %s
                          </p>
                        </td>
                      </tr>

                      <!-- ── Body ── -->
                      <tr>
                        <td style="background-color:#ffffff;padding:36px 40px;">

                          <p style="margin:0 0 20px;font-size:16px;color:#111827;line-height:1.5;">
                            Dear <strong>%s</strong>,
                          </p>

                          <p style="margin:0 0 28px;font-size:14px;color:#6b7280;line-height:1.8;">
                            We hope this message finds you well. This is a gentle reminder that the school fee(s) listed below are currently pending for the academic session <strong style="color:#374151;">%s</strong>.
                          </p>

                          <!-- Pending months box -->
                          <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
                            <tr>
                              <td style="background-color:#fef2f2;border:2px solid #fecaca;border-radius:12px;padding:22px 26px;">
                                <p style="margin:0 0 10px;font-size:11px;font-weight:700;color:#dc2626;letter-spacing:1.5px;text-transform:uppercase;">
                                  Pending Month(s)
                                </p>
                                <p style="margin:0 0 6px;font-size:20px;font-weight:800;color:#991b1b;">%s</p>
                                <p style="margin:0;font-size:12px;color:#b91c1c;">Academic Session: %s</p>
                              </td>
                            </tr>
                          </table>

                          <!-- Edunexify tip -->
                          <table width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
                            <tr>
                              <td style="background-color:#f0fdf4;border-left:4px solid #16a34a;padding:16px 18px;border-radius:0 8px 8px 0;">
                                <p style="margin:0;font-size:13px;color:#166534;line-height:1.7;">
                                  &#128161; <strong>Quick pay:</strong> You can clear dues instantly through the
                                  <strong>Edunexify</strong> app. Early payment avoids late fee charges.
                                </p>
                              </td>
                            </tr>
                          </table>

                          <p style="margin:0 0 32px;font-size:13.5px;color:#6b7280;line-height:1.8;">
                            If you have already made the payment, please disregard this message.
                            For any queries, feel free to contact the school office during working hours.
                          </p>

                          <hr style="border:none;border-top:1px solid #f1f5f9;margin:0 0 24px;">

                          <p style="margin:0;font-size:14px;color:#374151;line-height:1.7;">
                            With regards,<br>
                            <strong>%s</strong><br>
                            <span style="font-size:12px;color:#9ca3af;">Fee Management Team</span>
                          </p>
                        </td>
                      </tr>

                      <!-- ── Footer ── -->
                      <tr>
                        <td align="center" style="background-color:#1f2937;border-radius:0 0 16px 16px;padding:22px 40px;">
                          <p style="margin:0 0 6px;font-size:12px;color:rgba(255,255,255,0.55);">
                            This is an automated message. Please do not reply to this email.
                          </p>
                          <p style="margin:0;font-size:11px;color:rgba(255,255,255,0.35);">
                            &copy; %d %s. All rights reserved.
                          </p>
                        </td>
                      </tr>

                    </table>
                  </td>
                </tr>
              </table>
            </body>
            </html>
            """.formatted(safeSchool, session, studentName, session, monthList, session, safeSchool, year, safeSchool);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Maps an academic month number (1 = startMonth … 12 = startMonth-1) to the 1st day
     * of that calendar month, using the session's start and end years. Delegates to
     * FeeCalculationService, which now owns this logic so generation/backfill/recalculation
     * share the exact same academic-month-to-calendar-date math this reminder system already
     * relied on — see FeeCalculationService.academicMonthStart.
     */
    private LocalDate academicMonthStart(int academicMonth, int startYear, int endYear, int startMonth) {
        return feeCalculationService.academicMonthStart(academicMonth, startYear, endYear, startMonth);
    }

    /** Parses "2025-2026" → [2025, 2026]. Delegates to FeeCalculationService. */
    private int[] parseSession(String session) {
        return feeCalculationService.parseSession(session);
    }

    /** Returns the display name for an academic month (1 = the school's own start month).
     * Delegates to FeeCalculationService, which now owns the calendar-month array. */
    private String getMonthName(int academicMonth, int startMonth) {
        return feeCalculationService.getMonthName(academicMonth, startMonth);
    }

    /** Returns the academic year label (e.g. "2026-2027") for a given date and school start month. */
    private String getAcademicYear(LocalDate date, int startMonth) {
        int year = date.getYear();
        return (date.getMonthValue() >= startMonth)
                ? year + "-" + (year + 1)
                : (year - 1) + "-" + year;
    }

    /** Calendar month (1=Jan…12=Dec) → academic month (1 = startMonth). */
    private int getAcademicMonth(int calendarMonth, int startMonth) {
        return ((calendarMonth - startMonth + 12) % 12) + 1;
    }
}
