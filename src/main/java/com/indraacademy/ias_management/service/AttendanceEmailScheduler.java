package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.AttendanceRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class AttendanceEmailScheduler {

    private static final Logger log = LoggerFactory.getLogger(AttendanceEmailScheduler.class);

    @Autowired
    private AttendanceRepository attendanceRepository;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private EmailService emailService;

    @Scheduled(cron = "0 15 12 * * *")
    public void sendAttendanceEmails() {
        log.info("Starting scheduled job: sendAttendanceEmails at {}", LocalDate.now());
        final LocalDate today = LocalDate.now();
        List<Attendance> attendanceList = null;

        try {
            attendanceList = attendanceRepository.findByDate(today);
            if (attendanceList.isEmpty()) {
                log.info("No absent students found for today: {}", today);
                return;
            }
            log.info("Found {} absent records for today.", attendanceList.size());
        } catch (DataAccessException e) {
            log.error("Data access error while fetching absent students for date: {}", today, e);
            return;
        }

        for (Attendance attendance : attendanceList) {
            String studentId = attendance.getStudentId();

            if ("X".equals(studentId)) {
                log.warn("Skipping attendance record with non-standard student ID 'X'. Record details: {}", attendance.toString());
                continue;
            }

            Optional<Student> studentOptional = Optional.empty();
            try {
                studentOptional = studentRepository.findById(studentId);
            } catch (DataAccessException e) {
                log.error("Data access error while fetching Student ID: {}. Skipping email for this student.", studentId, e);
                continue;
            }

            if (studentOptional.isPresent()) {
                Student student = studentOptional.get();
                String parentEmail = student.getEmail();

                if (parentEmail != null && !parentEmail.trim().isEmpty()) {
                    try {
                        String subject = "Student Absence Notification";
                        String body = "Dear Parent,\n\n" +
                                "This is to inform you that your child, " + student.getName() +
                                ", was absent on " + today.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")) + ".\n\n" +
                                "Sincerely,\nIndra Academy Sr. Sec. School";

                        emailService.sendEmail(parentEmail, subject, body);
                        log.info("Successfully sent absence email to parent of student ID: {} ({})", studentId, parentEmail);
                    } catch (Exception e) {
                        log.error("Failed to send email to parent of student ID: {} ({})", studentId, parentEmail, e);
                    }
                } else {
                    log.warn("Parent/Guardian email not found or empty for student ID: {} (Name: {})", studentId, student.getName());
                }
            } else {
                log.warn("Student not found with ID: {}. Unable to send absence email.", studentId);
            }
        }
        log.info("Finished scheduled job: sendAttendanceEmails");
    }
}