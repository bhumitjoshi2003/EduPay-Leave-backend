package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.service.EmailService;
import com.indraacademy.ias_management.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class NoticeController {

    private static final Logger log = LoggerFactory.getLogger(NoticeController.class);

    @Autowired private EmailService emailService;
    @Autowired private NotificationService notificationService;

    /**
     * POST /api/admin/notice
     *
     * Request fields:
     *   title        — notification title (required)
     *   subject      — email subject line (required when deliveryMode is EMAIL or BOTH)
     *   body         — notice body text (required)
     *   targetClass  — audience selector (required); valid values:
     *                    "All"                           → all students
     *                    "<className>"                   → students of that class (e.g. "Class 10")
     *                    "ALL_TEACHERS"                  → all teachers
     *                    "CLASS_WITH_TEACHER:<className>" → students of that class + their class teacher
     *   deliveryMode — optional, defaults to BOTH; valid values:
     *                    "IN_APP" → save to notification table only, no email
     *                    "EMAIL"  → send email only, do not save in-app
     *                    "BOTH"   → send email AND save in-app
     */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PostMapping("/notice")
    public ResponseEntity<?> sendNotice(@RequestBody Map<String, Object> requestBody,
                                        HttpServletRequest request) {
        String title        = (String) requestBody.get("title");
        String subject      = (String) requestBody.get("subject");
        String body         = (String) requestBody.get("body");
        String targetClass  = (String) requestBody.get("targetClass");
        String deliveryMode = requestBody.getOrDefault("deliveryMode", "BOTH").toString().toUpperCase();

        log.info("Notice request — title: {}, targetClass: {}, deliveryMode: {}", title, targetClass, deliveryMode);

        // --- Validation ---
        if (title == null || title.isBlank()
                || body == null || body.isBlank()
                || targetClass == null || targetClass.isBlank()) {
            return ResponseEntity.badRequest().body("title, body and targetClass are required.");
        }
        if (!deliveryMode.equals("IN_APP") && !deliveryMode.equals("EMAIL") && !deliveryMode.equals("BOTH")) {
            return ResponseEntity.badRequest().body("deliveryMode must be IN_APP, EMAIL, or BOTH.");
        }
        boolean sendEmail  = deliveryMode.equals("EMAIL") || deliveryMode.equals("BOTH");
        boolean saveInApp  = deliveryMode.equals("IN_APP") || deliveryMode.equals("BOTH");

        if (sendEmail && (subject == null || subject.isBlank())) {
            return ResponseEntity.badRequest().body("subject is required when deliveryMode is EMAIL or BOTH.");
        }

        // --- Email dispatch ---
        if (sendEmail) {
            if ("ALL_TEACHERS".equalsIgnoreCase(targetClass)) {
                emailService.sendBulkEmailToTeachers(subject, body);
                log.info("Bulk email sent to all teachers.");
            } else if (targetClass.toUpperCase().startsWith("CLASS_WITH_TEACHER:")) {
                String className = targetClass.substring("CLASS_WITH_TEACHER:".length()).trim();
                emailService.sendBulkEmailToClassWithTeacher(subject, body, className);
                log.info("Bulk email sent to class {} and their class teacher.", className);
            } else {
                // "All" or a specific class name → students only
                emailService.sendBulkEmailToClass(subject, body, targetClass);
                log.info("Bulk email sent to class: {}", targetClass);
            }
        }

        // --- In-app notification ---
        if (saveInApp) {
            String audience = resolveAudience(targetClass);
            Notification notification = new Notification();
            notification.setTitle(title);
            notification.setMessage(body);
            notification.setType("NOTICE");
            notification.setAudience(audience);
            notificationService.createBroadNotification(notification, request);
            log.info("In-app notification saved with audience: {}", audience);
        }

        return ResponseEntity.ok(Map.of("message", "Notice sent successfully."));
    }

    /**
     * Maps a targetClass value to the audience string stored in the notifications table.
     *
     * "All"                            → "ALL"
     * "ALL_TEACHERS"                   → "TEACHERS"
     * "CLASS_WITH_TEACHER:<className>" → "CLASS_WITH_TEACHER:<className>"
     * "<className>"                    → "CLASS:<className>"
     */
    private String resolveAudience(String targetClass) {
        if ("All".equalsIgnoreCase(targetClass)) return "ALL";
        if ("ALL_TEACHERS".equalsIgnoreCase(targetClass)) return "TEACHERS";
        if (targetClass.toUpperCase().startsWith("CLASS_WITH_TEACHER:")) return targetClass;
        return "CLASS:" + targetClass;
    }
}
