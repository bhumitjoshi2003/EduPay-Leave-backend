package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * School admin's visibility overrides for plan-granted features.
 *
 * Admins can only HIDE features their plan already grants (overrideState = DISABLED).
 * They cannot ADD features beyond what the plan allows.
 * DEFAULT = follow plan (same as having no row — but stored for explicit audit trail).
 *
 * Applied during entitlement rebuild: plan features − DISABLED overrides → SchoolEntitlementFeature.
 */
@Entity
@Table(name = "school_feature_overrides")
@IdClass(SchoolFeatureId.class)
public class SchoolFeatureOverride {

    @Id
    @Column(name = "school_id")
    private Long schoolId;

    @Id
    @Column(name = "feature_key", length = 60)
    private String featureKey;

    /** DEFAULT (follow plan) or DISABLED (admin hid this feature). */
    @Column(name = "override_state", nullable = false, length = 20)
    private String overrideState = "DEFAULT";

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public SchoolFeatureOverride() {}

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getFeatureKey() { return featureKey; }
    public void setFeatureKey(String featureKey) { this.featureKey = featureKey; }

    public String getOverrideState() { return overrideState; }
    public void setOverrideState(String overrideState) { this.overrideState = overrideState; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
