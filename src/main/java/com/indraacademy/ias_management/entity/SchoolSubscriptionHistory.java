package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Immutable event log for subscription lifecycle changes.
 * One row per event — never updated, only inserted.
 *
 * Events:
 *   TRIAL_STARTED   — school onboarded and trial begins
 *   PLAN_ASSIGNED   — super admin manually assigns a plan
 *   PLAN_UPDATED    — super admin edits dates / notes
 *   PAYMENT_SUCCESS — admin pays via Razorpay, subscription activated
 *   STATUS_CHANGED  — nightly scheduler auto-transitions status
 */
@Entity
@Table(name = "school_subscription_history",
       indexes = { @Index(name = "idx_sub_history_school", columnList = "school_id") })
public class SchoolSubscriptionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    /** TRIAL_STARTED, PLAN_ASSIGNED, PLAN_UPDATED, PAYMENT_SUCCESS, STATUS_CHANGED */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "plan_name", length = 200)
    private String planName;

    /** Resulting subscription status after this event. */
    @Column(name = "status", length = 20)
    private String status;

    /** Human-readable summary (e.g., payment ID, admin note). */
    @Column(name = "notes", length = 500)
    private String notes;

    /** Who triggered the event (userId or "SCHEDULER"). */
    @Column(name = "performed_by", length = 100)
    private String performedBy;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    public SchoolSubscriptionHistory() {}

    public SchoolSubscriptionHistory(Long schoolId, String eventType, Long planId,
                                     String planName, String status,
                                     String notes, String performedBy) {
        this.schoolId    = schoolId;
        this.eventType   = eventType;
        this.planId      = planId;
        this.planName    = planName;
        this.status      = status;
        this.notes       = notes;
        this.performedBy = performedBy;
    }

    public Long getId()            { return id; }
    public Long getSchoolId()      { return schoolId; }
    public String getEventType()   { return eventType; }
    public Long getPlanId()        { return planId; }
    public String getPlanName()    { return planName; }
    public String getStatus()      { return status; }
    public String getNotes()       { return notes; }
    public String getPerformedBy() { return performedBy; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
}
