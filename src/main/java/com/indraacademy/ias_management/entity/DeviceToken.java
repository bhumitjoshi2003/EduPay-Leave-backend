package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "device_tokens",
        uniqueConstraints = @UniqueConstraint(columnNames = "token"),
        indexes = @Index(name = "idx_device_tokens_user_id", columnList = "user_id"))
public class DeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id")
    private Long schoolId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, unique = true, length = 4096)
    private String token;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public DeviceToken() {}

    public DeviceToken(String userId, String token, LocalDateTime createdAt) {
        this.userId = userId;
        this.token = token;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
}
