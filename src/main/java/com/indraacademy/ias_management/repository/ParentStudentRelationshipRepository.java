package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ParentStudentRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ParentStudentRelationshipRepository extends JpaRepository<ParentStudentRelationship, Long> {
    List<ParentStudentRelationship> findBySchoolIdAndParentIdOrderByPrimaryGuardianDescStudentIdAsc(Long schoolId, String parentId);
    List<ParentStudentRelationship> findBySchoolIdAndStudentIdOrderByPrimaryGuardianDesc(Long schoolId, String studentId);
    Optional<ParentStudentRelationship> findByIdAndSchoolId(Long id, Long schoolId);
    Optional<ParentStudentRelationship> findBySchoolIdAndParentIdAndStudentId(Long schoolId, String parentId, String studentId);
    @Query("select case when count(r) > 0 then true else false end from ParentStudentRelationship r " +
           "where r.schoolId = :schoolId and r.parentId = :parentId and r.studentId = :studentId " +
           "and r.active = true and r.effectiveFrom <= :today " +
           "and (r.effectiveUntil is null or r.effectiveUntil >= :today)")
    boolean hasActiveAccess(@Param("schoolId") Long schoolId,
                            @Param("parentId") String parentId,
                            @Param("studentId") String studentId,
                            @Param("today") LocalDate today);
}
