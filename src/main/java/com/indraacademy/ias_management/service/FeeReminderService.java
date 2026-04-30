package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.OverdueStudentDto;
import com.indraacademy.ias_management.entity.BusFees;
import com.indraacademy.ias_management.entity.FeeStructure;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.BusFeesRepository;
import com.indraacademy.ias_management.repository.FeeStructureRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
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
            String monthName = getMonthName(fee.getMonth());
            String subject = "Fee Payment Reminder - " + monthName;
            String body = String.format(
                    "Dear %s,\n\nThis is a friendly reminder from Indra Academy that the fees for the month of %s (%s) is pending.\n"
                            + "Kindly complete the payment to avoid late fee charges.\n\nThank you,\nIndra Academy Sr. Sec. School",
                    student.getName() != null ? student.getName() : "Student",
                    monthName,
                    fee.getYear()
            );
            log.info("Triggering scheduled reminder email to: {}", email);
            emailService.sendEmail(email, subject, body);
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

        // Fetch all unpaid fee records for the session (optionally filtered by class)
        List<StudentFees> unpaid = (className != null && !className.isBlank())
                ? studentFeesRepository.findAllUnpaidBySessionAndClassName(session, className)
                : studentFeesRepository.findAllUnpaidBySession(session);

        // Filter to months whose 1st day has already passed
        List<StudentFees> overdue = unpaid.stream()
                .filter(sf -> academicMonthStart(sf.getMonth(), startYear, endYear).isBefore(today))
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

            Optional<Student> studentOpt = studentRepository.findById(studentId);
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
                    c -> feeStructureRepository.findByAcademicYearAndClassName(session, c));
            double totalDue = fees.stream()
                    .mapToDouble(sf -> amountDueForMonth(fs, sf, session))
                    .sum();

            // Last payment date
            String lastPaymentDate = paymentRepository
                    .findLatestPaymentDateByStudentIdAndSession(studentId, session)
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
     * Sends a fee reminder email to a single student and logs the audit entry.
     * Returns true if the email was sent (student has an email), false otherwise.
     */
    public boolean sendReminder(String studentId, String session, HttpServletRequest request) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        String email = student.getEmail();
        if (email == null || email.isBlank()) {
            log.warn("Cannot send reminder: student {} has no email.", studentId);
            return false;
        }

        // Build list of overdue months for this student
        int[] years = parseSession(session);
        LocalDate today = LocalDate.now();

        List<StudentFees> overdueMonths = studentFeesRepository.findAllUnpaidBySessionAndClassName(session, student.getClassName())
                .stream()
                .filter(sf -> sf.getStudentId().equals(studentId))
                .filter(sf -> academicMonthStart(sf.getMonth(), years[0], years[1]).isBefore(today))
                .sorted(Comparator.comparingInt(StudentFees::getMonth))
                .collect(Collectors.toList());

        String monthList = overdueMonths.isEmpty()
                ? "upcoming months"
                : overdueMonths.stream().map(sf -> getMonthName(sf.getMonth())).collect(Collectors.joining(", "));

        String subject = "Fee Payment Reminder – " + session;
        String body = String.format(
                "Dear %s,\n\n"
                        + "This is a reminder from Indra Academy that school fees for the following month(s) are pending:\n"
                        + "%s (%s)\n\n"
                        + "Please complete the payment at your earliest convenience to avoid late fee charges.\n\n"
                        + "Thank you,\nIndra Academy Sr. Sec. School",
                student.getName() != null ? student.getName() : "Parent/Guardian",
                monthList,
                session
        );

        emailService.sendEmail(email, subject, body);
        log.info("Fee reminder sent to student {} ({})", studentId, email);

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
     * Sends reminders to all students in the list. Returns the count of successful sends.
     */
    public int sendBulkReminders(List<String> studentIds, String session, HttpServletRequest request) {
        int sent = 0;
        for (String studentId : studentIds) {
            try {
                boolean ok = sendReminder(studentId, session, request);
                if (ok) sent++;
            } catch (Exception e) {
                log.error("Failed to send reminder for student {}: {}", studentId, e.getMessage());
            }
        }
        return sent;
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
            java.math.BigDecimal busFee = busFeesRepository.findFeesByDistanceAndAcademicYear(sf.getDistance(), session);
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
        return (date.getMonthValue() >= 4) ? (year - 1) + "-" + year : (year - 2) + "-" + (year - 1);
    }

    private int getAcademicMonth(int month) {
        return (month >= 4) ? (month - 3) : (month + 9);
    }
}
