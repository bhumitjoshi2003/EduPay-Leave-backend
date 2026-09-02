package com.indraacademy.ias_management.dto;

import lombok.Data;
import java.time.LocalDateTime;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import com.indraacademy.ias_management.notification.NotificationPriority;

@Data
public class UserNotificationDTO {
    private Long id;
    /** Explicit recipient inbox-row identifier used by PUT /notification/user/{inboxId}/read. */
    private Long inboxId;
    private String userId;
    private String title;
    private String message;
    private String type;

    private Boolean isRead;
    private LocalDateTime createdAt;
    private LocalDateTime readAt;
    private NotificationEventCode eventCode;
    private NotificationCategory category;
    private NotificationPriority priority;
    private String sourceEntityType;
    private String sourceEntityId;
    private String actionRoute;
    private String actionMetadata;
    private LocalDateTime expiresAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Boolean getIsRead() {
        return isRead;
    }

    public void setIsRead(Boolean isRead) {
        this.isRead = isRead;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

}
