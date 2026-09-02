package com.indraacademy.ias_management.entity;

import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import com.indraacademy.ias_management.notification.NotificationPriority;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications", indexes = {
    @Index(name = "idx_notifications_created_at", columnList = "created_at"),
    @Index(name = "idx_notifications_created_by", columnList = "created_by")
})
@Data
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    @Column(nullable = false)
    private String type;

    @Column(name = "audience")
    private String audience;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "school_id")
    private Long schoolId;

    /** Delivery channel: PUSH, SMS, EMAIL, WHATSAPP. Null treated as PUSH for backward compatibility. */
    @Column(name = "channel", length = 20)
    private String channel;

    /** Left null on a transient instance so NotificationService.eventCodeFor() can tell
     *  "caller didn't set one" apart from "caller explicitly wants LEGACY_NOTIFICATION" and
     *  derive it from type instead. The persisted row is always given an explicit value by
     *  NotificationPublicationTransaction.toEntity(), so this is never actually null in the DB
     *  despite the NOT NULL column. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_code", nullable = false, length = 80)
    private NotificationEventCode eventCode;

    /** See eventCode above — same null-means-unset convention. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationPriority priority = NotificationPriority.NORMAL;

    @Column(name = "source_entity_type", length = 80)
    private String sourceEntityType;

    @Column(name = "source_entity_id", length = 255)
    private String sourceEntityId;

    @Column(name = "action_route", length = 500)
    private String actionRoute;

    @Column(name = "action_metadata", columnDefinition = "TEXT")
    private String actionMetadata;

    @Column(name = "actor_user_id", length = 100)
    private String actorUserId;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "idempotency_key", length = 180)
    private String idempotencyKey;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Notification(){ }

    public Notification(Long id, String title, String message, String type, String audience, LocalDateTime createdAt, String createdBy) {
        this.id = id;
        this.title = title;
        this.message = message;
        this.type = type;
        this.audience = audience;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
}
