package com.indraacademy.ias_management.dto;

/**
 * Body for DELETE /api/super-admin/plans/{planId}/features/{featureKey}
 * policy: IMMEDIATE | NEXT_MONTHLY | NEXT_QUARTERLY | NEXT_ANNUAL
 */
public class RemovePlanFeatureRequest {
    private String policy = "NEXT_MONTHLY";

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
}
