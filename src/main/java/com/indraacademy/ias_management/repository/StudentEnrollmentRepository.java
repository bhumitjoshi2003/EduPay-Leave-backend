package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

public interface StudentEnrollmentRepository extends JpaRepository<StudentEnrollment, Long> {

    List<StudentEnrollment> findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(
            Long schoolId, String studentId, Long academicSessionId);

    List<StudentEnrollment> findBySchoolIdAndStudentIdOrderByAcademicSessionIdAscEffectiveFromAsc(
            Long schoolId, String studentId);

    @Query("SELECT e FROM StudentEnrollment e WHERE e.schoolId = :schoolId " +
            "AND e.studentId = :studentId AND e.academicSessionId = :sessionId " +
            "AND e.status <> com.indraacademy.ias_management.entity.StudentEnrollmentStatus.CANCELLED " +
            "AND e.effectiveFrom <= :date " +
            "AND (e.effectiveUntil IS NULL OR e.effectiveUntil >= :date)")
    Optional<StudentEnrollment> findEffectiveEnrollment(
            @Param("schoolId") Long schoolId,
            @Param("studentId") String studentId,
            @Param("sessionId") Long academicSessionId,
            @Param("date") LocalDate date);

    @Query("SELECT e FROM StudentEnrollment e WHERE e.schoolId = :schoolId " +
            "AND e.studentId = :studentId AND e.academicSessionId = :sessionId " +
            "AND e.status IN (com.indraacademy.ias_management.entity.StudentEnrollmentStatus.ACTIVE, " +
            "com.indraacademy.ias_management.entity.StudentEnrollmentStatus.CLOSED) " +
            "AND e.effectiveFrom <= :date " +
            "AND (e.effectiveUntil IS NULL OR e.effectiveUntil >= :date) " +
            "ORDER BY e.effectiveFrom, e.id")
    List<StudentEnrollment> findRealizedEffectiveEnrollments(
            @Param("schoolId") Long schoolId,
            @Param("studentId") String studentId,
            @Param("sessionId") Long academicSessionId,
            @Param("date") LocalDate date);

    @Query("SELECT e FROM StudentEnrollment e WHERE e.schoolId = :schoolId " +
            "AND e.studentId = :studentId AND e.academicSessionId = :sessionId " +
            "AND e.status IN (com.indraacademy.ias_management.entity.StudentEnrollmentStatus.ACTIVE, " +
            "com.indraacademy.ias_management.entity.StudentEnrollmentStatus.CLOSED) " +
            "ORDER BY e.effectiveFrom, e.id")
    List<StudentEnrollment> findRealizedByStudentAndSession(
            @Param("schoolId") Long schoolId,
            @Param("studentId") String studentId,
            @Param("sessionId") Long academicSessionId);

    @Query("SELECT e FROM StudentEnrollment e WHERE e.schoolId = :schoolId " +
            "AND e.studentId = :studentId AND e.academicSessionId = :sessionId " +
            "AND e.status IN (com.indraacademy.ias_management.entity.StudentEnrollmentStatus.ACTIVE, " +
            "com.indraacademy.ias_management.entity.StudentEnrollmentStatus.CLOSED) " +
            "AND e.effectiveFrom <= :to " +
            "AND (e.effectiveUntil IS NULL OR e.effectiveUntil >= :from) " +
            "ORDER BY e.effectiveFrom, e.id")
    List<StudentEnrollment> findRealizedOverlappingRange(
            @Param("schoolId") Long schoolId,
            @Param("studentId") String studentId,
            @Param("sessionId") Long academicSessionId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT e FROM StudentEnrollment e WHERE e.schoolId = :schoolId " +
            "AND e.studentId = :studentId " +
            "AND e.status IN (com.indraacademy.ias_management.entity.StudentEnrollmentStatus.ACTIVE, " +
            "com.indraacademy.ias_management.entity.StudentEnrollmentStatus.CLOSED) " +
            "ORDER BY e.effectiveFrom, e.id")
    List<StudentEnrollment> findRealizedHistory(
            @Param("schoolId") Long schoolId,
            @Param("studentId") String studentId);

    Optional<StudentEnrollment> findBySchoolIdAndStudentIdAndAcademicSessionIdAndStatusInAndEffectiveUntilIsNull(
            Long schoolId, String studentId, Long academicSessionId,
            Collection<StudentEnrollmentStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM StudentEnrollment e WHERE e.schoolId = :schoolId " +
            "AND e.studentId = :studentId AND e.academicSessionId = :sessionId " +
            "ORDER BY e.effectiveFrom")
    List<StudentEnrollment> findHistoryForUpdate(
            @Param("schoolId") Long schoolId,
            @Param("studentId") String studentId,
            @Param("sessionId") Long academicSessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM StudentEnrollment e WHERE e.schoolId = :schoolId " +
            "AND e.studentId = :studentId ORDER BY e.academicSessionId, e.effectiveFrom")
    List<StudentEnrollment> findAllHistoryForUpdate(
            @Param("schoolId") Long schoolId,
            @Param("studentId") String studentId);

    List<StudentEnrollment> findBySchoolIdAndAcademicSessionIdAndClassIdOrderByEffectiveFromAsc(
            Long schoolId, Long academicSessionId, Long classId);

    List<StudentEnrollment> findBySchoolIdAndAcademicSessionIdOrderByStudentIdAscEffectiveFromAsc(
            Long schoolId, Long academicSessionId);

    List<StudentEnrollment> findBySchoolIdAndAcademicSessionIdAndClassIdAndSectionIdOrderByEffectiveFromAsc(
            Long schoolId, Long academicSessionId, Long classId, Long sectionId);

    @Query("SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END FROM StudentEnrollment e " +
            "WHERE e.schoolId = :schoolId AND e.studentId = :studentId " +
            "AND e.academicSessionId = :sessionId " +
            "AND e.status <> com.indraacademy.ias_management.entity.StudentEnrollmentStatus.CANCELLED " +
            "AND (:effectiveUntil IS NULL OR e.effectiveFrom <= :effectiveUntil) " +
            "AND (e.effectiveUntil IS NULL OR e.effectiveUntil >= :effectiveFrom)")
    boolean existsOverlappingEnrollment(
            @Param("schoolId") Long schoolId,
            @Param("studentId") String studentId,
            @Param("sessionId") Long academicSessionId,
            @Param("effectiveFrom") LocalDate effectiveFrom,
            @Param("effectiveUntil") LocalDate effectiveUntil);

    boolean existsByIdAndSchoolId(Long id, Long schoolId);

    /** Used by StudentService.deleteStudent to detect retained enrollment history: any
     * StudentEnrollment row (current or historical/closed) FK-references student(school_id,
     * student_id) with ON DELETE RESTRICT (see fk_student_enrollment_student), so it must
     * block hard deletion the same way retained financial history does — an established,
     * ordinarily-admitted student is removed via the exit workflow, never a hard delete. */
    boolean existsByStudentIdAndSchoolId(String studentId, Long schoolId);

    boolean existsBySchoolId(Long schoolId);

    boolean existsBySchoolIdAndSectionId(Long schoolId, Long sectionId);

    Optional<StudentEnrollment> findByIdAndSchoolId(Long id, Long schoolId);

    List<StudentEnrollment> findBySchoolIdAndStatusAndEffectiveFromLessThanEqualOrderByStudentIdAscEffectiveFromAsc(
            Long schoolId, StudentEnrollmentStatus status, LocalDate effectiveFrom);

    List<StudentEnrollment> findBySchoolIdAndStatusAndClosureReasonAndEffectiveUntilLessThanEqualOrderByStudentIdAscEffectiveUntilAsc(
            Long schoolId, StudentEnrollmentStatus status,
            com.indraacademy.ias_management.entity.StudentEnrollmentClosureReason closureReason,
            LocalDate effectiveUntil);

    // ─── E6C: realized-only roster queries for historical attendance class/school summaries ───

    @Query("SELECT e FROM StudentEnrollment e WHERE e.schoolId = :schoolId " +
            "AND e.academicSessionId = :sessionId AND e.classId = :classId " +
            "AND e.status IN (com.indraacademy.ias_management.entity.StudentEnrollmentStatus.ACTIVE, " +
            "com.indraacademy.ias_management.entity.StudentEnrollmentStatus.CLOSED) " +
            "AND e.effectiveFrom <= :to AND (e.effectiveUntil IS NULL OR e.effectiveUntil >= :from) " +
            "ORDER BY e.studentId, e.effectiveFrom")
    List<StudentEnrollment> findRealizedByAcademicSessionAndClassOverlappingRange(
            @Param("schoolId") Long schoolId,
            @Param("sessionId") Long academicSessionId,
            @Param("classId") Long classId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT e FROM StudentEnrollment e WHERE e.schoolId = :schoolId " +
            "AND e.academicSessionId = :sessionId AND e.classId = :classId AND e.sectionId = :sectionId " +
            "AND e.status IN (com.indraacademy.ias_management.entity.StudentEnrollmentStatus.ACTIVE, " +
            "com.indraacademy.ias_management.entity.StudentEnrollmentStatus.CLOSED) " +
            "AND e.effectiveFrom <= :to AND (e.effectiveUntil IS NULL OR e.effectiveUntil >= :from) " +
            "ORDER BY e.studentId, e.effectiveFrom")
    List<StudentEnrollment> findRealizedByAcademicSessionAndClassAndSectionOverlappingRange(
            @Param("schoolId") Long schoolId,
            @Param("sessionId") Long academicSessionId,
            @Param("classId") Long classId,
            @Param("sectionId") Long sectionId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT e FROM StudentEnrollment e WHERE e.schoolId = :schoolId " +
            "AND e.academicSessionId = :sessionId " +
            "AND e.status IN (com.indraacademy.ias_management.entity.StudentEnrollmentStatus.ACTIVE, " +
            "com.indraacademy.ias_management.entity.StudentEnrollmentStatus.CLOSED) " +
            "AND e.effectiveFrom <= :to AND (e.effectiveUntil IS NULL OR e.effectiveUntil >= :from) " +
            "ORDER BY e.studentId, e.effectiveFrom")
    List<StudentEnrollment> findRealizedByAcademicSessionOverlappingRange(
            @Param("schoolId") Long schoolId,
            @Param("sessionId") Long academicSessionId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
