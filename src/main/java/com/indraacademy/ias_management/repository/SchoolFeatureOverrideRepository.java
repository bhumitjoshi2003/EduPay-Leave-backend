package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.SchoolFeatureOverride;
import com.indraacademy.ias_management.entity.SchoolFeatureId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SchoolFeatureOverrideRepository extends JpaRepository<SchoolFeatureOverride, SchoolFeatureId> {

    List<SchoolFeatureOverride> findBySchoolId(Long schoolId);

    Optional<SchoolFeatureOverride> findBySchoolIdAndFeatureKey(Long schoolId, String featureKey);

    /** Returns keys the admin has explicitly set to DISABLED. */
    List<SchoolFeatureOverride> findBySchoolIdAndOverrideState(Long schoolId, String overrideState);
}
