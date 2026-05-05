package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.OverdueStudentDto;
import com.indraacademy.ias_management.entity.BusFees;
import com.indraacademy.ias_management.entity.FeeStructure;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.BusFeesRepository;
import com.indraacademy.ias_management.repository.FeeStructureRepository;
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

    private static final String[] MONTH_NAMES = {
            "April", "May", "June", "July", "August", "September",
            "October", "November", "December", "January", "February", "March"
    };

    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private FeeStructureRepository feeStructureRepository;
    @Autowired private BusFeesRepository busFeesRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private EmailService emailService;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;

    // ─── Scheduled reminder ───────────────────────────────────────────────────

    @Scheduled(cron = "0 0 6 28 * *")
    public void sendMonthlyFeeReminders() {
        LocalDate today = LocalDate.now();
        String academicYear = getAcademicYear(today);
        int academicMonth = getAcademicMonth(today.getMonthValue());

        log.info("Checking unpaid fees for Session: {} | Month: {}", academicYear, academicMonth);

        // NOTE: Scheduler runs platform-wide (all schools) — no schoolId filtering here
        List<StudentFees> unpaidFees = studentFeesRepository.findAllUnpaidByYearAndMonth(academicYear, academicMonth);

        for (StudentFees fee : unpaidFees) {
            try {
                processScheduledReminder(fee);
            } catch (Exception e) {
                log.error("Error processing reminder for student {}: {}", fee.getStudentId(), e.getMessage());
            }
        }
    }

    private void processScheduledReminder(StudentFees fee) {
        studentRepository.findById(fee.getStudentId()).ifPresentOrElse(student -> {
            String email = student.getEmail();
            if (email == null || email.isEmpty()) {
                log.warn("Skipping: Student {} has no email address.", student.getStudentId());
                return;
            }
            String schoolName = schoolRepository.findById(student.getSchoolId() != null ? student.getSchoolId() : -1L)
                    .map(School::getName).orElse("School");
            String monthName = getMonthName(fee.getMonth());
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
        // Fetch all unpaid fee records for the session (optionally filtered by class)
        List<StudentFees> unpaid = (className != null && !className.isBlank())
                ? studentFeesRepository.findAllUnpaidBySchoolIdAndSessionAndClassName(schoolId, session, className)
                : studentFeesRepository.findAllUnpaidBySchoolIdAndSession(schoolId, session);

        // Filter to months that have already started (1st day <= start of current month)
        LocalDate currentMonthStart = today.withDayOfMonth(1);
        List<StudentFees> overdue = unpaid.stream()
                .filter(sf -> !academicMonthStart(sf.getMonth(), startYear, endYear).isAfter(currentMonthStart))
                .collect(Collectors.toList());

        // Group by studentId
        Map<String, List<StudentFees>> byStudent = overdue.stream()
                .collect(Collectors.groupingBy(StudentFees::getStudentId));

        // Cache FeeStructure per class to avoid repeated DB hits
        Map<String, FeeStructure> feeStructureByClass = new HashMap<>();

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
                    .map(sf -> getMonthName(sf.getMonth()))
                    .collect(Collectors.toList());

            // totalDue: sum each month's exact amount due (mirrors what the student sees on their receipt)
            String cls = student.getClassName();
            FeeStructure fs = feeStructureByClass.computeIfAbsent(cls,
                    c -> feeStructureRepository.findByAcademicYearAndClassNameAndSchoolId(session, c, securityUtil.getSchoolId()));
            double totalDue = fees.stream()
                    .mapToDouble(sf -> amountDueForMonth(fs, sf, session))
                    .sum();

            // Last payment date
            String lastPaymentDate = paymentRepository
                    .findLatestPaymentDateByStudentIdAndSchoolIdAndSession(studentId, schoolId, session)
                    .map(dt -> dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                    .orElse(null);

            // daysOverdue = today − 1st of the oldest overdue month
            LocalDate oldestMonthStart = academicMonthStart(fees.get(0).getMonth(), startYear, endYear);
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
     * Sends the reminder email for one student. Returns the month list string on success,
     * or null if the student has no email (caller skips audit logging in that case).
     */
    private String sendReminderEmail(String studentId, String session) {
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId())
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        String email = student.getEmail();
        if (email == null || email.isBlank()) {
            log.warn("Cannot send reminder: student {} has no email.", studentId);
            return null;
        }

        int[] years = parseSession(session);
        LocalDate currentMonthStart = LocalDate.now().withDayOfMonth(1);

        List<StudentFees> overdueMonths = studentFeesRepository.findAllUnpaidBySchoolIdAndSessionAndClassName(securityUtil.getSchoolId(), session, student.getClassName())
                .stream()
                .filter(sf -> sf.getStudentId().equals(studentId))
                .filter(sf -> !academicMonthStart(sf.getMonth(), years[0], years[1]).isAfter(currentMonthStart))
                .sorted(Comparator.comparingInt(StudentFees::getMonth))
                .collect(Collectors.toList());

        String monthList = overdueMonths.isEmpty()
                ? "upcoming months"
                : overdueMonths.stream().map(sf -> getMonthName(sf.getMonth())).collect(Collectors.joining(", "));

        String subject = "Fee Payment Reminder – " + session;
        String studentName = student.getName() != null ? student.getName() : "Parent/Guardian";
        String schoolName = schoolRepository.findById(securityUtil.getSchoolId())
                .map(School::getName).orElse("School");
        String htmlBody = buildFeeReminderHtml(studentName, monthList, session, schoolName);

        emailService.sendHtmlEmail(email, subject, htmlBody);
        log.info("Fee reminder sent to student {} ({})", studentId, email);
        return monthList;
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
     * Returns the exact school fee due for a single month, matching what appears on the student's receipt.
     *
     * Month 1 (April) = Tuition + Annual Charges + ECA/Project + Examination Fee + Lab Charges [+ Bus]
     * All other months = Tuition [+ Bus]
     *
     * Platform fee is excluded — it is a payment-gateway charge, not a school fee.
     */
    private double amountDueForMonth(FeeStructure fs, StudentFees sf, String session) {
        if (fs == null) return 0.0;

        double amount = fs.getTuitionFee();

        // April (month 1 of academic year) carries all one-time annual components
        if (sf.getMonth() == 1) {
            amount += fs.getAnnualCharges();
            amount += fs.getEcaProject();
            amount += fs.getExaminationFee();
            amount += fs.getLabCharges();
        }

        // Add bus fee if the student uses the bus for this month's record
        if (Boolean.TRUE.equals(sf.getTakesBus()) && sf.getDistance() != null && sf.getDistance() > 0) {
            java.math.BigDecimal busFee = busFeesRepository.findFeesByDistanceAndAcademicYearAndSchoolId(sf.getDistance(), session, securityUtil.getSchoolId());
            if (busFee != null) {
                amount += busFee.doubleValue();
            }
        }

        return amount;
    }

    /** Maps academic month number (1=April … 12=March) to the 1st day of that calendar month. */
    private LocalDate academicMonthStart(int academicMonth, int startYear, int endYear) {
        if (academicMonth <= 9) {
            return LocalDate.of(startYear, academicMonth + 3, 1);
        } else {
            return LocalDate.of(endYear, academicMonth - 9, 1);
        }
    }

    /** Parses "2025-2026" → [2025, 2026]. */
    private int[] parseSession(String session) {
        String[] parts = session.split("-");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid session format. Expected 'YYYY-YYYY'.");
        return new int[]{ Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
    }

    private String getMonthName(int month) {
        return (month >= 1 && month <= 12) ? MONTH_NAMES[month - 1] : "Unknown";
    }

    private String getAcademicYear(LocalDate date) {
        int year = date.getYear();
        // April–December belongs to current year's academic session (e.g. April 2026 → "2026-2027")
        // January–March belongs to previous year's session (e.g. March 2026 → "2025-2026")
        return (date.getMonthValue() >= 4) ? year + "-" + (year + 1) : (year - 1) + "-" + year;
    }

    private int getAcademicMonth(int month) {
        return (month >= 4) ? (month - 3) : (month + 9);
    }
}
