package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.SchoolSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    /**
     * Subscriptions in TRIAL or ACTIVE state whose expiry (expiresAt or trialEndsAt)
     * falls within the given window. Used by the expiry notification scheduler.
     */
    @Query("SELECT s FROM SchoolSubscription s WHERE s.status IN ('TRIAL', 'ACTIVE') " +
           "AND ((s.expiresAt IS NOT NULL AND s.expiresAt BETWEEN :from AND :to) " +
           "OR (s.trialEndsAt IS NOT NULL AND s.trialEndsAt BETWEEN :from AND :to))")
    List<SchoolSubscription> findExpiringSoon(@Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to);
}
