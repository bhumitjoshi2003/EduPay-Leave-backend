package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentFeeAssignment;
import com.indraacademy.ias_management.entity.StudentFeeAssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface StudentFeeAssignmentRepository extends JpaRepository<StudentFeeAssignment, Long> {
    Optional<StudentFeeAssignment> findBySchoolIdAndStudentIdAndAcademicSession(Long schoolId, String studentId, String academicSession);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM StudentFeeAssignment a WHERE a.schoolId = :schoolId AND a.studentId = :studentId AND a.academicSession = :session")
    Optional<StudentFeeAssignment> findForGenerationUpdate(@Param("schoolId") Long schoolId,
            @Param("studentId") String studentId, @Param("session") String session);
    List<StudentFeeAssignment> findBySchoolIdAndAcademicSession(Long schoolId, String academicSession);
    long countBySchoolIdAndAcademicSessionAndStatus(Long schoolId, String academicSession, StudentFeeAssignmentStatus status);

    /** Financial AcademicSession Authority, Phase D4 — the authoritative-identity counterpart to
     * {@link #findBySchoolIdAndStudentIdAndAcademicSession}: selects by {@code academicSessionId}
     * (the resolved AcademicSession's real id) instead of the raw, display-only
     * {@code academicSession} label. Used only where the caller already holds a non-null id from
     * an already-resolved AcademicSession — which is now every operational write site,
     * {@code markGenerationFailed}'s bookkeeping path included (see its own comment for why that
     * migration is safe). The label-based method above is kept as compatibility surface with
     * zero live callers, never removed. */
    Optional<StudentFeeAssignment> findBySchoolIdAndStudentIdAndAcademicSessionId(Long schoolId, String studentId, Long academicSessionId);

    /** Same authoritative-identity selection as {@link #findBySchoolIdAndStudentIdAndAcademicSessionId},
     * with the identical row-level write lock as {@link #findForGenerationUpdate} — only the
     * selection predicate differs (id instead of label); lock mode, transaction assumptions, and
     * cardinality are unchanged. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM StudentFeeAssignment a WHERE a.schoolId = :schoolId AND a.studentId = :studentId AND a.academicSessionId = :academicSessionId")
    Optional<StudentFeeAssignment> findForGenerationUpdateByAcademicSessionId(@Param("schoolId") Long schoolId,
            @Param("studentId") String studentId, @Param("academicSessionId") Long academicSessionId);
}
