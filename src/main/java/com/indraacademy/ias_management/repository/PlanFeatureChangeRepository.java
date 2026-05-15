package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.PlanFeatureChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PlanFeatureChangeRepository extends JpaRepository<PlanFeatureChange, Long> {
    List<PlanFeatureChange> findByPlanIdOrderByCreatedAtDesc(Long planId);
    /** Used by the scheduler to find pending removals whose effective time has arrived. */
    List<PlanFeatureChange> findByActionTypeAndAppliedFalseAndEffectiveAtBefore(
            String actionType, LocalDateTime now);
    /** Used to find and cancel existing pending removals when a new removal or re-add overrides them. */
    List<PlanFeatureChange> findByPlanIdAndFeatureKeyAndActionTypeAndApplied(
            Long planId, String featureKey, String actionType, boolean applied);
}
