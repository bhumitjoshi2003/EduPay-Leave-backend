package com.indraacademy.ias_management.dto;

/** Request body for updating a school-level feature override (admin only). */
public class SchoolFeatureOverrideRequest {

    /** DEFAULT or DISABLED */
    private String overrideState;

    public String getOverrideState() { return overrideState; }
    public void setOverrideState(String overrideState) { this.overrideState = overrideState; }
}
