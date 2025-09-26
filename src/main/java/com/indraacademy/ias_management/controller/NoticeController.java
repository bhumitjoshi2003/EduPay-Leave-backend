package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.service.EmailService;
import com.indraacademy.ias_management.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;


@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "http://localhost:4200")
public class NoticeController {

    private static final Logger log = LoggerFactory.getLogger(NoticeController.class);

    @Autowired
    private StudentRepository studentRepository; // Note: This repository should ideally be accessed via a Service layer.

    @Autowired private EmailService emailService;

    @Autowired private JwtUtil jwtUtil; // Note: Not used in the controller, could be removed if unnecessary.

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PostMapping("/notice")
    public ResponseEntity<?> sendEmailToStudents(@RequestBody Map<String, Object> requestBody) {
        String title = (String) requestBody.get("title");
        String subject = (String) requestBody.get("subject");
        String body = (String) requestBody.get("body");
        String selectedClass = (String) requestBody.get("targetClass");

        log.info("Request to send bulk email notice (Title: {}) to class: {}", title, selectedClass);

        if (title == null || title.isEmpty() || subject == null || subject.isEmpty() || body == null || body.isEmpty() || selectedClass == null || selectedClass.isEmpty()) {
            log.warn("Notice failed validation: Missing required fields.");
            return ResponseEntity.badRequest().body("Title, subject, body and class are required.");
        }

        try {
            emailService.sendBulkEmailToClass(subject, body, selectedClass);
            log.info("Emails sent successfully to class: {}", selectedClass);
            return ResponseEntity.ok(Map.of("message", "Emails sent successfully to selected class students."));
        } catch (IllegalArgumentException e) {
            log.error("Email service failed with bad request for class {}: {}", selectedClass, e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("An unexpected error occurred while sending bulk email notice to class: {}", selectedClass, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to send emails due to an internal error.");
        }
    }
}