package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.Date;

@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true, length = 100)
    private String userId;

    @Column(length = 255)
    private String password;

    @Column(length = 20)
    private String role;

    @Column(length = 255)
    private String email;

    @Column(length = 100)
    private String resetToken;

    @Temporal(TemporalType.TIMESTAMP)
    private Date resetTokenExpiry;

    @Column(name = "school_id")
    private Long schoolId;

    /**
     * Stores the current valid refresh token JTI (JWT ID).
     * Set on login/refresh, cleared on logout.
     * A refresh token whose JTI does not match this value is considered revoked.
     */
    @Column(name = "refresh_token_id")
    private String refreshTokenId;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getResetToken() {
        return resetToken;
    }

    public void setResetToken(String resetToken) {
        this.resetToken = resetToken;
    }

    public Date getResetTokenExpiry() {
        return resetTokenExpiry;
    }

    public void setResetTokenExpiry(Date resetTokenExpiry) {
        this.resetTokenExpiry = resetTokenExpiry;
    }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getRefreshTokenId() { return refreshTokenId; }
    public void setRefreshTokenId(String refreshTokenId) { this.refreshTokenId = refreshTokenId; }
}