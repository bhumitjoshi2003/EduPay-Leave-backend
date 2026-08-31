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

    /**
     * One aggregate query for a whole page of parents' active-link counts, instead of
     * fetching each parent's full relationship list and counting in Java (see
     * ParentPortalService#listParentsPaged — this replaces an N+1 for the directory listing).
     * A relationship's active flag is already kept in sync with the linked student's exit
     * status (see V38 migration / endRelationshipsForExitedStudent), so active=true alone
     * is a reliable "currently linked" signal here without a further per-row student lookup.
     */
    @Query("select r.parentId as parentId, count(r) as total from ParentStudentRelationship r " +
           "where r.schoolId = :schoolId and r.parentId in :parentIds and r.active = true " +
           "group by r.parentId")
    List<ParentLinkCount> countActiveByParentIds(@Param("schoolId") Long schoolId,
                                                 @Param("parentIds") List<String> parentIds);

    interface ParentLinkCount {
        String getParentId();
        long getTotal();
    }

    long countBySchoolIdAndActiveTrue(Long schoolId);

    @Query("select count(distinct r.parentId) from ParentStudentRelationship r " +
           "where r.schoolId = :schoolId and r.active = true")
    long countDistinctActiveParents(@Param("schoolId") Long schoolId);
}
