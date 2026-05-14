package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.PlanFeature;
import com.indraacademy.ias_management.entity.PlanFeatureId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlanFeatureRepository extends JpaRepository<PlanFeature, PlanFeatureId> {
    List<PlanFeature> findByPlanId(Long planId);
    void deleteByPlanIdAndFeatureKey(Long planId, String featureKey);
    boolean existsByPlanIdAndFeatureKey(Long planId, String featureKey);
}
