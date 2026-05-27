package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "notification_channels",
    uniqueConstraints = @UniqueConstraint(columnNames = {"school_id", "channel_type"}))
public class NotificationChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    /** PUSH, SMS, EMAIL, WHATSAPP */
    @Column(name = "channel_type", length = 20, nullable = false)
    private String channelType;

    @Column(nullable = false)
    private boolean enabled;

    /** JSON blob for channel-specific config (e.g. API keys, sender ID). Null for PUSH (uses FCM). */
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    public NotificationChannel() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
}
