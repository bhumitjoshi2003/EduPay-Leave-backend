package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlanRepository extends JpaRepository<Plan, Long> {
    List<Plan> findByIsActiveTrueOrderByPriorityScoreAsc();
    List<Plan> findAllByOrderByPriorityScoreAsc();
    boolean existsByTierIgnoreCase(String tier);
}
