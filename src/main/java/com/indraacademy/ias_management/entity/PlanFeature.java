package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Pure join table — records which features a plan currently has.
 * This table represents the CURRENT STATE only.
 * All change history and scheduling lives in PlanFeatureChange.
 */
@Entity
@Table(name = "plan_features")
@IdClass(PlanFeatureId.class)
public class PlanFeature {

    @Id
    @Column(name = "plan_id")
    private Long planId;

    @Id
    @Column(name = "feature_key", length = 60)
    private String featureKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public PlanFeature() {}

    public PlanFeature(Long planId, String featureKey) {
        this.planId     = planId;
        this.featureKey = featureKey;
    }

    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }

    public String getFeatureKey() { return featureKey; }
    public void setFeatureKey(String featureKey) { this.featureKey = featureKey; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
