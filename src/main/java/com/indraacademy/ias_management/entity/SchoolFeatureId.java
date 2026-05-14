package com.indraacademy.ias_management.entity;

import java.io.Serializable;
import java.util.Objects;

/** Shared composite PK class for school-scoped feature tables (schoolId + featureKey). */
public class SchoolFeatureId implements Serializable {

    private Long schoolId;
    private String featureKey;

    public SchoolFeatureId() {}

    public SchoolFeatureId(Long schoolId, String featureKey) {
        this.schoolId   = schoolId;
        this.featureKey = featureKey;
    }

    public Long getSchoolId() { return schoolId; }
    public String getFeatureKey() { return featureKey; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SchoolFeatureId that)) return false;
        return Objects.equals(schoolId, that.schoolId) && Objects.equals(featureKey, that.featureKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schoolId, featureKey);
    }
}
