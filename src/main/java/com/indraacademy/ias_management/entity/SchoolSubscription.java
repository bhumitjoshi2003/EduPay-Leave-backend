package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One row per school — tracks the full subscription lifecycle.
 * Created when a super admin assigns a plan to a school (via SubscriptionController).
 * Status transitions (TRIAL → ACTIVE → GRACE → EXPIRED) are driven by EntitlementRefreshService.
 */
@Entity
@Table(name = "school_subscriptions",
       uniqueConstraints = @UniqueConstraint(name = "uk_school_subscription", columnNames = "school_id"))
public class SchoolSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** TRIAL, ACTIVE, GRACE, EXPIRED */
    @Column(nullable = false, length = 20)
    private String status = "TRIAL";

    @Column(name = "trial_start_at")
    private LocalDateTime trialStartAt;

    @Column(name = "trial_ends_at")
    private LocalDateTime trialEndsAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    /** When the paid subscription period ends (before grace). */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** When the grace period ends — access fully cut off after this. */
    @Column(name = "grace_ends_at")
    private LocalDateTime graceEndsAt;

    /** UserId of the super admin who created / last modified this subscription. */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(length = 500)
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public SchoolSubscription() {}

    public Long getId() { return id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getTrialStartAt() { return trialStartAt; }
    public void setTrialStartAt(LocalDateTime trialStartAt) { this.trialStartAt = trialStartAt; }

    public LocalDateTime getTrialEndsAt() { return trialEndsAt; }
    public void setTrialEndsAt(LocalDateTime trialEndsAt) { this.trialEndsAt = trialEndsAt; }

    public LocalDateTime getActivatedAt() { return activatedAt; }
    public void setActivatedAt(LocalDateTime activatedAt) { this.activatedAt = activatedAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getGraceEndsAt() { return graceEndsAt; }
    public void setGraceEndsAt(LocalDateTime graceEndsAt) { this.graceEndsAt = graceEndsAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
