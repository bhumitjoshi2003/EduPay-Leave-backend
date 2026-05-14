package com.indraacademy.ias_management.dto;

/** Request body for assigning or updating a school's subscription (super admin only). */
public class SchoolSubscriptionRequest {

    private Long planId;

    /** TRIAL, ACTIVE, GRACE, EXPIRED */
    private String status;

    /** ISO date-time string, e.g. "2026-03-31T23:59:59" */
    private String trialStartAt;
    private String trialEndsAt;
    private String activatedAt;
    private String expiresAt;
    private String graceEndsAt;

    private String notes;

    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTrialStartAt() { return trialStartAt; }
    public void setTrialStartAt(String trialStartAt) { this.trialStartAt = trialStartAt; }

    public String getTrialEndsAt() { return trialEndsAt; }
    public void setTrialEndsAt(String trialEndsAt) { this.trialEndsAt = trialEndsAt; }

    public String getActivatedAt() { return activatedAt; }
    public void setActivatedAt(String activatedAt) { this.activatedAt = activatedAt; }

    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }

    public String getGraceEndsAt() { return graceEndsAt; }
    public void setGraceEndsAt(String graceEndsAt) { this.graceEndsAt = graceEndsAt; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
