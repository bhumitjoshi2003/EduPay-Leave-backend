package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Resolved runtime subscription state for a school.
 * One row per school — rebuilt by EntitlementRefreshService on every relevant change.
 *
 * This is NOT a cache. It is the authoritative read-model for all access-control decisions:
 *   - hasFeature()     reads SchoolEntitlementFeature (companion table)
 *   - checkLimit()     reads the limit fields here
 *   - getEffectivePlan() returns this entity directly
 *   - resolvedPriorityScore helps debug when multiple grants/plans overlap
 */
@Entity
@Table(name = "school_effective_entitlements")
public class SchoolEffectiveEntitlement {

    /** schoolId is the PK — one row per school. */
    @Id
    @Column(name = "school_id")
    private Long schoolId;

    // ── Plan identity (denormalized for fast reads) ──────────────────────────

    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "plan_name", length = 200)
    private String planName;

    @Column(name = "plan_tier", length = 50)
    private String planTier;

    @Column(name = "plan_version", length = 20)
    private String planVersion;

    /**
     * Priority score copied from the winning plan at rebuild time.
     * Useful when debugging overlapping grants — higher score won.
     */
    @Column(name = "resolved_priority_score")
    private Integer resolvedPriorityScore;

    // ── Subscription lifecycle ───────────────────────────────────────────────

    /** TRIAL, ACTIVE, GRACE, EXPIRED */
    @Column(name = "subscription_status", length = 20)
    private String subscriptionStatus;

    /** PLAN, GRANT, OVERRIDE — extensible for future entitlement sources. */
    @Column(name = "entitlement_source", length = 30)
    private String entitlementSource = "PLAN";

    @Column(name = "trial_ends_at")
    private LocalDateTime trialEndsAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "grace_ends_at")
    private LocalDateTime graceEndsAt;

    // ── Student limits (copied from plan at rebuild time) ────────────────────

    @Column(name = "max_students")
    private Integer maxStudents;

    @Column(name = "student_soft_limit_pct")
    private Integer studentSoftLimitPct = 90;

    @Column(name = "student_hard_limit_pct")
    private Integer studentHardLimitPct = 105;

    // ── Staff limits ─────────────────────────────────────────────────────────

    @Column(name = "max_staff")
    private Integer maxStaff;

    @Column(name = "staff_soft_limit_pct")
    private Integer staffSoftLimitPct = 90;

    @Column(name = "staff_hard_limit_pct")
    private Integer staffHardLimitPct = 105;

    // ── Storage limits ────────────────────────────────────────────────────────

    @Column(name = "storage_gb_limit")
    private Integer storageGbLimit;

    @Column(name = "storage_soft_limit_pct")
    private Integer storageSoftLimitPct = 90;

    @Column(name = "storage_hard_limit_pct")
    private Integer storageHardLimitPct = 105;

    // ── Rebuild metadata ─────────────────────────────────────────────────────

    @Column(name = "last_rebuilt_at")
    private LocalDateTime lastRebuiltAt;

    /**
     * What triggered the last rebuild:
     * SCHEDULER, PLAN_CHANGE, SUBSCRIPTION_CHANGE, OVERRIDE_CHANGE, MANUAL
     */
    @Column(name = "rebuilt_by", length = 50)
    private String rebuiltBy;

    public SchoolEffectiveEntitlement() {}

    // ── Getters & Setters ────────────────────────────────────────────────────

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }

    public String getPlanName() { return planName; }
    public void setPlanName(String planName) { this.planName = planName; }

    public String getPlanTier() { return planTier; }
    public void setPlanTier(String planTier) { this.planTier = planTier; }

    public String getPlanVersion() { return planVersion; }
    public void setPlanVersion(String planVersion) { this.planVersion = planVersion; }

    public Integer getResolvedPriorityScore() { return resolvedPriorityScore; }
    public void setResolvedPriorityScore(Integer resolvedPriorityScore) { this.resolvedPriorityScore = resolvedPriorityScore; }

    public String getSubscriptionStatus() { return subscriptionStatus; }
    public void setSubscriptionStatus(String subscriptionStatus) { this.subscriptionStatus = subscriptionStatus; }

    public String getEntitlementSource() { return entitlementSource; }
    public void setEntitlementSource(String entitlementSource) { this.entitlementSource = entitlementSource; }

    public LocalDateTime getTrialEndsAt() { return trialEndsAt; }
    public void setTrialEndsAt(LocalDateTime trialEndsAt) { this.trialEndsAt = trialEndsAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getGraceEndsAt() { return graceEndsAt; }
    public void setGraceEndsAt(LocalDateTime graceEndsAt) { this.graceEndsAt = graceEndsAt; }

    public Integer getMaxStudents() { return maxStudents; }
    public void setMaxStudents(Integer maxStudents) { this.maxStudents = maxStudents; }

    public Integer getStudentSoftLimitPct() { return studentSoftLimitPct; }
    public void setStudentSoftLimitPct(Integer studentSoftLimitPct) { this.studentSoftLimitPct = studentSoftLimitPct; }

    public Integer getStudentHardLimitPct() { return studentHardLimitPct; }
    public void setStudentHardLimitPct(Integer studentHardLimitPct) { this.studentHardLimitPct = studentHardLimitPct; }

    public Integer getMaxStaff() { return maxStaff; }
    public void setMaxStaff(Integer maxStaff) { this.maxStaff = maxStaff; }

    public Integer getStaffSoftLimitPct() { return staffSoftLimitPct; }
    public void setStaffSoftLimitPct(Integer staffSoftLimitPct) { this.staffSoftLimitPct = staffSoftLimitPct; }

    public Integer getStaffHardLimitPct() { return staffHardLimitPct; }
    public void setStaffHardLimitPct(Integer staffHardLimitPct) { this.staffHardLimitPct = staffHardLimitPct; }

    public Integer getStorageGbLimit() { return storageGbLimit; }
    public void setStorageGbLimit(Integer storageGbLimit) { this.storageGbLimit = storageGbLimit; }

    public Integer getStorageSoftLimitPct() { return storageSoftLimitPct; }
    public void setStorageSoftLimitPct(Integer storageSoftLimitPct) { this.storageSoftLimitPct = storageSoftLimitPct; }

    public Integer getStorageHardLimitPct() { return storageHardLimitPct; }
    public void setStorageHardLimitPct(Integer storageHardLimitPct) { this.storageHardLimitPct = storageHardLimitPct; }

    public LocalDateTime getLastRebuiltAt() { return lastRebuiltAt; }
    public void setLastRebuiltAt(LocalDateTime lastRebuiltAt) { this.lastRebuiltAt = lastRebuiltAt; }

    public String getRebuiltBy() { return rebuiltBy; }
    public void setRebuiltBy(String rebuiltBy) { this.rebuiltBy = rebuiltBy; }
}
