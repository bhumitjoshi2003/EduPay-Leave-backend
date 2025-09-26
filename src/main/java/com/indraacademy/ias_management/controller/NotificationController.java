package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.UserNotificationDTO;
import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@RestController
@RequestMapping("api/notification")
public class NotificationController {

    @Autowired private AuthService authService;
    @Autowired private NotificationService notificationService;
    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<?> createNotification(@RequestBody Notification notification, @RequestHeader(name="authorization") String authorizationHeader){
        log.info("Request to create broad notification with title: {}", notification.getTitle());
        try{
            Notification createdNotification = notificationService.createBroadNotification(notification, authorizationHeader);
            log.info("Notification created successfully with ID: {}", createdNotification.getId());
            return new ResponseEntity<>(createdNotification, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            log.error("Error creating notification (Bad Request): {}", e.getMessage());
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            log.error("An unexpected error occurred during notification creation.", e);
            return new ResponseEntity<>("An internal server error occurred.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/{id}")
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
    public ResponseEntity<List<UserNotificationDTO>> getUserNotifications(
            @RequestHeader(name = "authorization") String authorizationHeader) {
        String userId = authService.getUserIdFromToken(authorizationHeader);
        String userRole = authService.getRoleFromToken(authorizationHeader);
        log.info("Fetching notifications for userId: {} with role: {}", userId, userRole);
        try {
            List<UserNotificationDTO> userNotifications = notificationService.getNotificationsForUser(userId, userRole);
            return new ResponseEntity<>(userNotifications, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Error fetching user notifications for {}: {}", userId, e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/all")
    public ResponseEntity<List<Notification>> getAllNotifications() {
        log.info("Fetching all notifications.");
        try {
            List<Notification> notifications = notificationService.getAllNotifications();
            return new ResponseEntity<>(notifications, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Error fetching all notifications.", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PutMapping("/{id}")
    public ResponseEntity<Notification> updateNotification(@PathVariable Long id, @RequestBody Notification notification, @RequestHeader(name="authorization") String authorizationHeader) {
        log.info("Updating notification with ID: {}", id);
        try {
            Notification savedNotification = notificationService.updateNotification(id, notification, authorizationHeader);
            log.info("Notification updated successfully with ID: {}", id);
            return new ResponseEntity<>(savedNotification, HttpStatus.OK);
        } catch (NoSuchElementException e) {
            log.error("Error updating notification with ID {}: Not found.", id);
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException e) {
            log.error("Error updating notification with ID {}: Bad Request - {}", id, e.getMessage());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            log.error("An unexpected error occurred during notification update for ID {}.", id, e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNotification(@PathVariable Long id) {
        log.warn("Request to delete notification with ID: {}", id);
        try {
            notificationService.deleteNotification(id);
            log.info("Notification deleted successfully with ID: {}", id);
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        } catch (NoSuchElementException e) {
            log.error("Error deleting notification with ID {}: Not found.", id);
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            log.error("An unexpected error occurred during notification deletion for ID {}.", id, e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/user/unread/count")
    public ResponseEntity<Long> getUnreadNotificationCount(
            @RequestHeader(name = "authorization") String authorizationHeader) {
        String userId = authService.getUserIdFromToken(authorizationHeader);
        String userRole = authService.getRoleFromToken(authorizationHeader);
        log.info("Fetching unread notification count for userId: {} with role: {}", userId, userRole);
        try {
            long count = notificationService.getUnreadNotificationCount(userId, userRole);
            return new ResponseEntity<>(count, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Error fetching unread notification count for {}: {}", userId, e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/user/read-all")
    public ResponseEntity<Void> markAllNotificationsAsRead(
            @RequestHeader(name = "authorization") String authorizationHeader) {
        String userId = authService.getUserIdFromToken(authorizationHeader);
        log.info("Marking all notifications as read for user ID {}", userId);
        try {
            notificationService.markAllNotificationsAsRead(userId);
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        } catch (Exception e) {
            log.error("Error marking all notifications as read for user {}: {}", userId, e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}