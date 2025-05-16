package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.service.EmailService;
import com.indraacademy.ias_management.service.UserDetailsServiceImpl;
import com.indraacademy.ias_management.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "http://localhost:4200")
public class NoticeController {

    @Autowired
    private StudentRepository studentRepository;

    @Autowired private EmailService emailService;

    @Autowired private JwtUtil jwtUtil;

    @PostMapping("/notice")
    public ResponseEntity<?> sendEmailToStudents(@RequestBody Map<String, Object> requestBody) {
        String title = (String) requestBody.get("title");
        String subject = (String) requestBody.get("subject");
        String body = (String) requestBody.get("body");
        String selectedClass = (String) requestBody.get("targetClass");

        if (title == null || title.isEmpty() || subject == null || subject.isEmpty() || body == null || body.isEmpty() || selectedClass == null || selectedClass.isEmpty()) {
            return ResponseEntity.badRequest().body("Title, subject, body and class are required.");
        }

        emailService.sendBulkEmailToClass(subject, body, selectedClass);
        return ResponseEntity.ok(Map.of("message", "Emails sent successfully to selected class students."));
    }
}
