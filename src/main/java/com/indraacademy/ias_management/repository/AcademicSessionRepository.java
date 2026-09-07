package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AcademicSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AcademicSessionRepository extends JpaRepository<AcademicSession, Long> {

    List<AcademicSession> findBySchoolIdOrderByStartDateDesc(Long schoolId);

    Optional<AcademicSession> findBySchoolIdAndLabel(Long schoolId, String label);

    Optional<AcademicSession> findBySchoolIdAndCurrentTrue(Long schoolId);

    boolean existsBySchoolIdAndLabel(Long schoolId, String label);

    Optional<AcademicSession> findByIdAndSchoolId(Long id, Long schoolId);

    /** Phase F4: pessimistic row lock, same pattern as SectionRepository's own
     *  findByIdAndSchoolIdForUpdate — serializes concurrent class-teacher-responsibility writes
     *  against a concurrent activation apply() for the same session (see
     *  TimetableSessionAccessService, ClassTeacherActivationService). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AcademicSession a WHERE a.id = :id AND a.schoolId = :schoolId")
    Optional<AcademicSession> findByIdAndSchoolIdForUpdate(@Param("id") Long id, @Param("schoolId") Long schoolId);

    /** The session whose [startDate, endDate] range contains the given date, inclusive of
     *  both boundaries. Assumes a school's sessions don't overlap — true by construction
     *  today (nothing creates overlapping sessions) but not yet enforced by a constraint. */
    Optional<AcademicSession> findBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long schoolId, LocalDate date, LocalDate sameDate);

    List<AcademicSession> findAllBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long schoolId, LocalDate date, LocalDate sameDate);

    Optional<AcademicSession> findFirstBySchoolIdAndStartDateLessThanOrderByStartDateDesc(
            Long schoolId, LocalDate startDate);

    Optional<AcademicSession> findFirstBySchoolIdAndStartDateGreaterThanOrderByStartDateAsc(
            Long schoolId, LocalDate startDate);
}
