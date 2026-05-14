package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Audit log and scheduling table for plan feature changes.
 *
 * When a feature is added:   action_type=ADD,    applied immediately → plan_features row inserted.
 * When a feature is removed: action_type=REMOVE, applied at effective_at → plan_features row deleted.
 *
 * A scheduler runs periodically, finds unapplied REMOVE rows where effective_at <= NOW(),
 * deletes the plan_features row, and sets applied=true here.
 */
@Entity
@Table(name = "plan_feature_changes",
        indexes = @Index(name = "idx_pfc_plan_feature", columnList = "plan_id, feature_key"))
public class PlanFeatureChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "feature_key", nullable = false, length = 60)
    private String featureKey;

    /** ADD or REMOVE */
    @Column(name = "action_type", nullable = false, length = 10)
    private String actionType;

    /**
     * For REMOVE actions: IMMEDIATE, NEXT_MONTHLY, NEXT_QUARTERLY, NEXT_ANNUAL.
     * Null for ADD (always immediate).
     */
    @Column(length = 30)
    private String policy;

    /**
     * The timestamp at which this change should be applied to plan_features.
     * For ADD: same as created_at (already applied).
     * For REMOVE: calculated from policy by the service layer.
     */
    @Column(name = "effective_at")
    private LocalDateTime effectiveAt;

    /** Set to true once the scheduler (or service) has applied this change. */
    @Column(nullable = false)
    private boolean applied = false;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public PlanFeatureChange() {}

    public Long getId() { return id; }

    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }

    public String getFeatureKey() { return featureKey; }
    public void setFeatureKey(String featureKey) { this.featureKey = featureKey; }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }

    public LocalDateTime getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(LocalDateTime effectiveAt) { this.effectiveAt = effectiveAt; }

    public boolean isApplied() { return applied; }
    public void setApplied(boolean applied) { this.applied = applied; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
