package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.PlanFeatureChange;

import java.time.LocalDateTime;

public class PlanFeatureChangeResponse {
    private Long id;
    private String featureKey;
    private String actionType;
    private String policy;
    private LocalDateTime effectiveAt;
    private boolean applied;
    private String createdBy;
    private LocalDateTime createdAt;

    public static PlanFeatureChangeResponse from(PlanFeatureChange c) {
        PlanFeatureChangeResponse r = new PlanFeatureChangeResponse();
        r.id          = c.getId();
        r.featureKey  = c.getFeatureKey();
        r.actionType  = c.getActionType();
        r.policy      = c.getPolicy();
        r.effectiveAt = c.getEffectiveAt();
        r.applied     = c.isApplied();
        r.createdBy   = c.getCreatedBy();
        r.createdAt   = c.getCreatedAt();
        return r;
    }

    public Long getId() { return id; }
    public String getFeatureKey() { return featureKey; }
    public String getActionType() { return actionType; }
    public String getPolicy() { return policy; }
    public LocalDateTime getEffectiveAt() { return effectiveAt; }
    public boolean isApplied() { return applied; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
