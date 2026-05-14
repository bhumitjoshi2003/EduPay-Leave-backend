package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.SchoolEntitlementFeature;
import com.indraacademy.ias_management.entity.SchoolFeatureId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SchoolEntitlementFeatureRepository extends JpaRepository<SchoolEntitlementFeature, SchoolFeatureId> {

    List<SchoolEntitlementFeature> findBySchoolId(Long schoolId);

    void deleteBySchoolId(Long schoolId);

    boolean existsBySchoolIdAndFeatureKey(Long schoolId, String featureKey);
}
