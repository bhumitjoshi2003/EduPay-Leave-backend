package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.StudentFees;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FeeReminderService {

    private static final Logger log = LoggerFactory.getLogger(FeeReminderService.class);

    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private EmailService emailService;
    @Autowired private StudentRepository studentRepository;
    /**
     * Runs on the scheduled time.
     * Current Cron: 08:04:03 AM daily
     */
    @Scheduled(cron = "0 0 6 28 * *")
    public void sendMonthlyFeeReminders() {
        LocalDate today = LocalDate.now();
        String academicYear = getAcademicYear(today);
        int academicMonth = getAcademicMonth(today.getMonthValue());

        log.info("Checking unpaid fees for Session: {} | Month: {}", academicYear, academicMonth);

        List<StudentFees> unpaidFees = studentFeesRepository.findAllUnpaidByYearAndMonth(academicYear, academicMonth);

        for (StudentFees fee : unpaidFees) {
            try {
                processReminder(fee);
            } catch (Exception e) {
                log.error("Error processing reminder for student {}: {}", fee.getStudentId(), e.getMessage());
            }
        }
    }

    private void processReminder(StudentFees fee) {
        studentRepository.findById(fee.getStudentId()).ifPresentOrElse(student -> {

            String email = student.getEmail();
            String firstName = student.getName();

            if (email == null || email.isEmpty()) {
                log.warn("Skipping: Student {} has no email address.", student.getStudentId());
                return;
            }

            String monthName = getMonthName(fee.getMonth());
            String subject = "Fee Payment Reminder - " + monthName;

            String body = String.format(
                    "Dear %s,\n\n" +
                            "This is a friendly reminder from Indra Academy that the fees for the month of %s (%s) is pending.\n" +
                            "Kindly complete the payment to avoid late fee charges.\n\n" +
                            "Thank you,\nIndra Academy Sr. Sec. School",
                    (firstName != null) ? firstName : "Student",
                    monthName,
                    fee.getYear()
            );

            log.info("Triggering email to: {}", email);
            emailService.sendEmail(email, subject, body);

        }, () -> log.error("Database Error: Student ID {} not found in Student table.", fee.getStudentId()));
    }

    private String getMonthName(int month) {
        String[] months = {"April", "May", "June", "July", "August", "September",
                "October", "November", "December", "January", "February", "March"};
        return (month >= 1 && month <= 12) ? months[month - 1] : "Unknown Month";
    }

    private String getAcademicYear(LocalDate date) {
        int year = date.getYear();
        return (date.getMonthValue() >= 4) ? (year-1) + "-" + (year) : (year - 2) + "-" + (year-1);
    }

    private int getAcademicMonth(int month) {
        return (month >= 4) ? (month - 3) : (month + 9);
    }
}