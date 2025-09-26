package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Autowired private JavaMailSender javaMailSender;
    @Autowired private StudentRepository studentRepository;

    @Value("${spring.mail.username}")
    private String emailSender;

    @Async
    public void sendEmail(String to, String subject, String body) {
        if (to == null || to.trim().isEmpty() || subject == null || body == null) {
            log.warn("Attempted to send email with missing required field (To: {}, Subject: {}). Aborting.", to, subject);
            return;
        }

        log.info("Attempting to send async email to: {} with subject: {}", to, subject);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(emailSender);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);

            javaMailSender.send(message);
            log.info("Successfully sent async email to: {}", to);
        } catch (MailException e) {
            log.error("MailException occurred while sending async email to: {} with subject: {}", to, subject, e);
            // Since this is @Async, re-throwing may not be effective for the caller,
            // but logging is critical.
        } catch (Exception e) {
            log.error("Unexpected error occurred while sending async email to: {} with subject: {}", to, subject, e);
        }
    }

    public void sendBulkEmail(List<String> toEmails, String subject, String body) {
        if (toEmails == null || toEmails.isEmpty() || subject == null || body == null) {
            log.warn("Attempted to send bulk email with missing required fields. Aborting.");
            return;
        }

        // Filter and clean the list before sending
        List<String> validEmails = toEmails.stream()
                .filter(email -> email != null && !email.trim().isEmpty())
                .toList();

        if (validEmails.isEmpty()) {
            log.warn("Filtered email list for bulk send is empty. Aborting.");
            return;
        }

        log.info("Attempting to send bulk email to {} unique recipients with subject: {}", validEmails.size(), subject);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(emailSender);
            message.setSubject(subject);
            message.setText(body);
            // Using setBcc is standard for bulk emails to protect recipient privacy
            message.setBcc(validEmails.toArray(new String[0]));

            javaMailSender.send(message);
            log.info("Successfully sent bulk email to {} recipients.", validEmails.size());
        } catch (MailException e) {
            log.error("MailException occurred while sending bulk email to {} recipients with subject: {}", validEmails.size(), subject, e);
        } catch (Exception e) {
            log.error("Unexpected error occurred while sending bulk email to {} recipients with subject: {}", validEmails.size(), subject, e);
        }
    }

    public void sendBulkEmailToClass(String subject, String body, String selectedClass) {
        if (subject == null || body == null || selectedClass == null || selectedClass.trim().isEmpty()) {
            log.warn("Attempted to send bulk email to class with missing required fields. Class: {}. Aborting.", selectedClass);
            return;
        }
        log.info("Fetching student list to send email to class: {} with subject: {}", selectedClass, subject);

        List<Student> students = Collections.emptyList();
        try {
            if ("All".equalsIgnoreCase(selectedClass)) {
                students = studentRepository.findAll();
                log.debug("Fetched all students for bulk email.");
            } else {
                students  = studentRepository.findByClassName(selectedClass);
                log.debug("Fetched {} students for class: {}", students.size(), selectedClass);
            }
        } catch (DataAccessException e) {
            log.error("Data access error occurred while fetching students for class: {}. Aborting email send.", selectedClass, e);
            return;
        }

        List<String> toEmails = students.stream()
                .map(Student::getEmail)
                .filter(email -> email != null && !email.trim().isEmpty())
                .distinct()
                .toList();

        if (toEmails.isEmpty()) {
            log.warn("No valid emails found for class: {}. Aborting email send.", selectedClass);
            return;
        }

        log.info("Found {} unique email addresses for class: {}", toEmails.size(), selectedClass);
        sendBulkEmail(toEmails, subject, body);
    }
}