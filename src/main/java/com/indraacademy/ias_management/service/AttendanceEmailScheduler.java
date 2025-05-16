package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.AttendanceRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class AttendanceEmailScheduler {

    @Autowired
    private AttendanceRepository attendanceRepository;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private EmailService emailService;

    @Scheduled(cron = "0 15 12 * * *")
    public void sendAttendanceEmails() {
        LocalDate today = LocalDate.now();
        List<Attendance> attendanceList = attendanceRepository.findByAbsentDate(today);

        for (Attendance attendance : attendanceList) {
            if (!"X".equals(attendance.getStudentId())) {
                Student student = studentRepository.findById(attendance.getStudentId()).orElse(null);
                if (student != null) {
                    String parentEmail = student.getEmail();
                    if (parentEmail != null && !parentEmail.isEmpty()) {
                        String subject = "Student Absence Notification";
                        String body = "Dear Parent,\n\n" +
                                "This is to inform you that your child, " + student.getName() +
                                ", was absent on " + today.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")) + ".\n\n" +
                                "Sincerely,\nIndra Academy Sr. Sec. School";
                        emailService.sendEmail(parentEmail, subject, body);
                    } else {
                        System.err.println("Parent/Guardian email not found for student: " + student.getStudentId());
                    }
                } else {
                    System.err.println("Student not found with ID: " + attendance.getStudentId());
                }
            }
        }
    }
}