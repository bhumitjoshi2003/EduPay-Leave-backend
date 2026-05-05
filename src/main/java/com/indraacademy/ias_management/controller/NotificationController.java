package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.UserNotificationDTO;
import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/notification")
public class NotificationController {

    @Autowired private AuthService authService;
    @Autowired private NotificationService notificationService;
    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PostMapping
    public ResponseEntity<?> createNotification(@RequestBody Notification notification, HttpServletRequest request) {
        log.info("Request to create broad notification with title: {}", notification.getTitle());
        Notification createdNotification = notificationService.createBroadNotification(notification, request);
        log.info("Notification created successfully with ID: {}", createdNotification.getId());
        return new ResponseEntity<>(createdNotification, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    public ResponseEntity<Notification> getNotificationById(@PathVariable Long id) {
        log.info("Fetching notification with ID: {}", id);
        Optional<Notification> notification = notificationService.getNotificationById(id);
        return notification.map(value -> new ResponseEntity<>(value, HttpStatus.OK))
                .orElseGet(() -> {
                    log.warn("Notification with ID {} not found.", id);
                    return new ResponseEntity<>(HttpStatus.NOT_FOUND);
                });
    }

    @GetMapping("/user")
    public ResponseEntity<Page<UserNotificationDTO>> getUserNotifications(Pageable pageable) {
        String userId = authService.getUserId();
        String userRole = authService.getRole();
        log.info("Fetching notifications for userId: {} with role: {}", userId, userRole);
        Page<UserNotificationDTO> userNotifications = notificationService.getNotificationsForUser(userId, userRole, pageable);
        return new ResponseEntity<>(userNotifications, HttpStatus.OK);
    }

    @GetMapping("/all")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    public ResponseEntity<Page<Notification>> getAllNotifications(Pageable pageable) {
        log.info("Fetching all notifications.");
        Page<Notification> notifications = notificationService.getAllNotifications(pageable);
        return new ResponseEntity<>(notifications, HttpStatus.OK);
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @PutMapping("/{id}")
    public ResponseEntity<Notification> updateNotification(@PathVariable Long id, @RequestBody Notification notification, HttpServletRequest request) {
        log.info("Updating notification with ID: {}", id);
        Notification savedNotification = notificationService.updateNotification(id, notification, request);
        log.info("Notification updated successfully with ID: {}", id);
        return new ResponseEntity<>(savedNotification, HttpStatus.OK);
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.SUPER_ADMIN + "')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNotification(@PathVariable Long id, HttpServletRequest request) {
        log.warn("Request to delete notification with ID: {}", id);
        notificationService.deleteNotification(id, request);
        log.info("Notification deleted successfully with ID: {}", id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @GetMapping("/user/unread/count")
    public ResponseEntity<Long> getUnreadNotificationCount() {
        String userId = authService.getUserId();
        String userRole = authService.getRole();
        log.info("Fetching unread notification count for userId: {} with role: {}", userId, userRole);
        long count = notificationService.getUnreadNotificationCount(userId, userRole);
        return new ResponseEntity<>(count, HttpStatus.OK);
    }

    @PutMapping("/user/read-all")
    public ResponseEntity<Void> markAllNotificationsAsRead() {
        String userId = authService.getUserId();
        log.info("Marking all notifications as read for user ID {}", userId);
        notificationService.markAllNotificationsAsRead(userId);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}