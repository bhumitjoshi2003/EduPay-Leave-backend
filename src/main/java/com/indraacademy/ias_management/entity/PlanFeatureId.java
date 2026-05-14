package com.indraacademy.ias_management.entity;

import java.io.Serializable;
import java.util.Objects;

public class PlanFeatureId implements Serializable {

    private Long planId;
    private String featureKey;

    public PlanFeatureId() {}

    public PlanFeatureId(Long planId, String featureKey) {
        this.planId     = planId;
        this.featureKey = featureKey;
    }

    public Long getPlanId() { return planId; }
    public String getFeatureKey() { return featureKey; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlanFeatureId)) return false;
        PlanFeatureId that = (PlanFeatureId) o;
        return Objects.equals(planId, that.planId) && Objects.equals(featureKey, that.featureKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(planId, featureKey);
    }
}
