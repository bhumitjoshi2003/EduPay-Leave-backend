package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.SchoolSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SchoolSubscriptionRepository extends JpaRepository<SchoolSubscription, Long> {

    Optional<SchoolSubscription> findBySchoolId(Long schoolId);

    boolean existsBySchoolId(Long schoolId);

    /** Find all school IDs currently on a specific plan. */
    @Query("SELECT s.schoolId FROM SchoolSubscription s WHERE s.planId = :planId")
    List<Long> findSchoolIdsByPlanId(Long planId);

    /** All non-expired subscriptions (for nightly status transition jobs). */
    @Query("SELECT s FROM SchoolSubscription s WHERE s.status <> 'EXPIRED'")
    List<SchoolSubscription> findAllActive();
}
