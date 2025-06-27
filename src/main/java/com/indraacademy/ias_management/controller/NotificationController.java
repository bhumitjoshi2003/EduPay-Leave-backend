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
import com.indraacademy.ias_management.entity.UserNotification;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("api/notification")
public class NotificationController {

    @Autowired private AuthService authService;
    @Autowired private NotificationService notificationService;
    private static final Logger logger = LoggerFactory.getLogger(NotificationController.class);

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<?> createNotification(@RequestBody Notification notification, @RequestHeader(name="authorization") String authorizationHeader){
        logger.info("Received notification object for creation: {}", notification);
        try{
            Notification createdNotification = notificationService.createBroadNotification(notification, authorizationHeader);
            return new ResponseEntity<>(createdNotification, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            logger.error("Error creating notification: {}", e.getMessage());
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) { // Catch any other unexpected exceptions
            logger.error("An unexpected error occurred during notification creation: {}", e.getMessage(), e);
            return new ResponseEntity<>("An internal server error occurred.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Notification> getNotificationById(@PathVariable Long id) {
        logger.info("Fetching notification with ID: {}", id);
        Optional<Notification> notification = notificationService.getNotificationById(id);
        return notification.map(value -> new ResponseEntity<>(value, HttpStatus.OK))
                .orElseGet(() -> {
                    logger.warn("Notification with ID {} not found.", id);
                    return new ResponseEntity<>(HttpStatus.NOT_FOUND);
                });
    }

    @GetMapping("/user")
    public ResponseEntity<List<UserNotificationDTO>> getUserNotifications(
            @RequestHeader(name = "authorization") String authorizationHeader) {
        try {
            String userId = authService.getUserIdFromToken(authorizationHeader); // Get userId from token
            String userRole = authService.getRoleFromToken(authorizationHeader); // Get user role from token
            logger.info("Fetching notifications for userId: {} with role: {}", userId, userRole);
            List<UserNotificationDTO> userNotifications = notificationService.getNotificationsForUser(userId, userRole);
            return new ResponseEntity<>(userNotifications, HttpStatus.OK);
        } catch (Exception e) {
            logger.error("Error fetching user notifications: {}", e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/all")
    public ResponseEntity<List<Notification>> getAllNotifications() {
        logger.info("Fetching all notifications.");
        List<Notification> notifications = notificationService.getAllNotifications();
        return new ResponseEntity<>(notifications, HttpStatus.OK);
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @PutMapping("/{id}")
    public ResponseEntity<Notification> updateNotification(@PathVariable Long id, @RequestBody Notification notification, @RequestHeader(name="authorization") String authorizationHeader) {
        logger.info("Updating notification with ID: {}", id);
        try {
            Notification savedNotification = notificationService.updateNotification(id, notification, authorizationHeader);
            return new ResponseEntity<>(savedNotification, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            logger.error("Error updating notification with ID {}: {}", id, e.getMessage());
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            logger.error("An unexpected error occurred during notification update for ID {}: {}", id, e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNotification(@PathVariable Long id) {
        logger.info("Deleting notification with ID: {}", id);
        try {
            notificationService.deleteNotification(id);
            return new ResponseEntity<>(HttpStatus.NO_CONTENT); // 204 No Content for successful deletion
        } catch (IllegalArgumentException e) {
            logger.error("Error deleting notification with ID {}: {}", id, e.getMessage());
            return new ResponseEntity<>(HttpStatus.NOT_FOUND); // Or BAD_REQUEST if the ID is just invalid
        } catch (Exception e) {
            logger.error("An unexpected error occurred during notification deletion for ID {}: {}", id, e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/user/unread/count")
    public ResponseEntity<Long> getUnreadNotificationCount(
            @RequestHeader(name = "authorization") String authorizationHeader) {
        try {
            String userId = authService.getUserIdFromToken(authorizationHeader);
            String userRole = authService.getRoleFromToken(authorizationHeader);
            logger.info("Fetching unread notification count for userId: {} with role: {}", userId, userRole);
            long count = notificationService.getUnreadNotificationCount(userId, userRole);
            return new ResponseEntity<>(count, HttpStatus.OK);
        } catch (Exception e) {
            logger.error("Error fetching unread notification count: {}", e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/user/read-all")
    public ResponseEntity<Void> markAllNotificationsAsRead(
            @RequestHeader(name = "authorization") String authorizationHeader) {
        try {
            String userId = authService.getUserIdFromToken(authorizationHeader);
            logger.info("Marking all notifications as read for user ID {}", userId);
            notificationService.markAllNotificationsAsRead(userId);
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        } catch (Exception e) {
            logger.error("Error marking all notifications as read for user: {}", e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}