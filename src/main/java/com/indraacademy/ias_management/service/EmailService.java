package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EmailService {

    @Autowired private JavaMailSender javaMailSender;
    @Autowired private StudentRepository studentRepository;

    @Value("${spring.mail.username}")
    private String emailSender;

    @Async
    public void sendEmail(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(emailSender);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        javaMailSender.send(message);
    }

    public void sendBulkEmail(List<String> toEmails, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(emailSender);
        message.setSubject(subject);
        message.setText(body);
        message.setBcc(toEmails.toArray(new String[0]));
        javaMailSender.send(message);
    }

    public void sendBulkEmailToClass(String subject, String body, String selectedClass) {
        List<Student> students;
        if (selectedClass.equals("All")) {
            students = studentRepository.findAll();
        } else {
            students  = studentRepository.findByClassName(selectedClass);
        }

        List<String> toEmails = students.stream()
                .map(Student::getEmail)
                .filter(email -> email != null && !email.trim().isEmpty())
                .distinct()
                .toList();

        sendBulkEmail(toEmails, subject, body);
    }
}
