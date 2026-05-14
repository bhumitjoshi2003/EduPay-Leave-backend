package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Per-school effective feature set — one row per (schoolId, featureKey).
 * Rebuilt by EntitlementRefreshService: plan features minus school DISABLED overrides.
 *
 * sourcePlanId and addedAt enable audit/debug of feature inheritance.
 * Example: "Why does school 42 have EXAM_MARKS? It came from plan 3 (Academy v1) on 2026-01-15."
 */
@Entity
@Table(name = "school_entitlement_features")
@IdClass(SchoolFeatureId.class)
public class SchoolEntitlementFeature {

    @Id
    @Column(name = "school_id")
    private Long schoolId;

    @Id
    @Column(name = "feature_key", length = 60)
    private String featureKey;

    /** The plan that granted this feature at the last entitlement rebuild. */
    @Column(name = "source_plan_id")
    private Long sourcePlanId;

    /** Timestamp of the last entitlement rebuild that wrote this row. */
    @Column(name = "added_at", nullable = false, updatable = false)
    private LocalDateTime addedAt;

    @PrePersist
    protected void onPersist() {
        addedAt = LocalDateTime.now();
    }

    public SchoolEntitlementFeature() {}

    public SchoolEntitlementFeature(Long schoolId, String featureKey, Long sourcePlanId) {
        this.schoolId    = schoolId;
        this.featureKey  = featureKey;
        this.sourcePlanId = sourcePlanId;
        this.addedAt     = LocalDateTime.now();
    }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getFeatureKey() { return featureKey; }
    public void setFeatureKey(String featureKey) { this.featureKey = featureKey; }

    public Long getSourcePlanId() { return sourcePlanId; }
    public void setSourcePlanId(Long sourcePlanId) { this.sourcePlanId = sourcePlanId; }

    public LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(LocalDateTime addedAt) { this.addedAt = addedAt; }
}
