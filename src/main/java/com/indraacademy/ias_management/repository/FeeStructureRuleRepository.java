package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.FeeStructureRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface FeeStructureRuleRepository extends JpaRepository<FeeStructureRule, Long> {

    List<FeeStructureRule> findBySchoolIdAndAcademicSessionIdAndClassName(
            Long schoolId, Long academicSessionId, String className);

    List<FeeStructureRule> findBySchoolIdAndAcademicSessionId(
            Long schoolId, Long academicSessionId);

    /**
     * Find all active rules for a class on a given date.
     * A rule is active if effectiveFrom <= date AND (effectiveUntil IS NULL OR effectiveUntil >= date).
     */
    @Query("SELECT r FROM FeeStructureRule r WHERE r.schoolId = :schoolId " +
            "AND r.academicSession.id = :sessionId AND r.className = :className " +
            "AND r.effectiveFrom <= :asOfDate " +
            "AND (r.effectiveUntil IS NULL OR r.effectiveUntil >= :asOfDate)")
    List<FeeStructureRule> findActiveRules(
            @Param("schoolId") Long schoolId,
            @Param("sessionId") Long sessionId,
            @Param("className") String className,
            @Param("asOfDate") LocalDate asOfDate);

    @Modifying
    @Query("DELETE FROM FeeStructureRule r WHERE r.schoolId = :schoolId AND r.academicSession.id = :sessionId AND r.className = :className")
    void deleteBySchoolIdAndAcademicSessionIdAndClassName(
            @Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId, @Param("className") String className);

    List<FeeStructureRule> findBySchoolId(Long schoolId);

    @Modifying
    @Query("DELETE FROM FeeStructureRule r WHERE r.schoolId = :schoolId AND r.academicSession.id = :sessionId")
    void deleteBySchoolIdAndAcademicSessionId(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId);

    boolean existsBySchoolIdAndFeeHeadId(Long schoolId, Long feeHeadId);
}
