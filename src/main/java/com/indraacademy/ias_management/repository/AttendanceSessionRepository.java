package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AttendanceSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AttendanceSessionRepository extends JpaRepository<AttendanceSession, Long> {

    /** Exact scope: sectionId null matches only a class-level (sectionless) submission. */
    String EXACT_SCOPE = "s.schoolId = :schoolId AND s.academicSessionId = :sessionId AND s.classId = :classId "
            + "AND ((:sectionId IS NULL AND s.sectionId IS NULL) OR s.sectionId = :sectionId) ";

    @Query("SELECT s FROM AttendanceSession s WHERE " + EXACT_SCOPE + "AND s.attendanceDate = :date")
    Optional<AttendanceSession> findSubmission(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId,
                                               @Param("classId") Long classId, @Param("sectionId") Long sectionId,
                                               @Param("date") LocalDate date);

    /** Same as {@link #findSubmission} but row-locked, so concurrent submissions for one day serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AttendanceSession s WHERE " + EXACT_SCOPE + "AND s.attendanceDate = :date")
    Optional<AttendanceSession> lockSubmission(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId,
                                               @Param("classId") Long classId, @Param("sectionId") Long sectionId,
                                               @Param("date") LocalDate date);

    /**
     * Creates the submission row unless it already exists (a concurrent or repeated submit), so a
     * double-click can never create two submissions or fail on the unique index. Returns 1 when
     * this call created it. Two statements because the unique index is partial on section_id.
     */
    @Modifying
    @Query(value = "INSERT INTO attendance_session (revision, school_id, academic_session_id, class_id, section_id, "
            + "attendance_date, marked_by_user_id, marked_at, updated_at) "
            + "VALUES (0, :schoolId, :sessionId, :classId, :sectionId, :date, :userId, :now, :now) "
            + "ON CONFLICT (school_id, academic_session_id, class_id, section_id, attendance_date) "
            + "WHERE section_id IS NOT NULL DO NOTHING", nativeQuery = true)
    int insertSectionSubmissionIfAbsent(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId,
                                        @Param("classId") Long classId, @Param("sectionId") Long sectionId,
                                        @Param("date") LocalDate date, @Param("userId") String userId,
                                        @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = "INSERT INTO attendance_session (revision, school_id, academic_session_id, class_id, section_id, "
            + "attendance_date, marked_by_user_id, marked_at, updated_at) "
            + "VALUES (0, :schoolId, :sessionId, :classId, NULL, :date, :userId, :now, :now) "
            + "ON CONFLICT (school_id, academic_session_id, class_id, attendance_date) "
            + "WHERE section_id IS NULL DO NOTHING", nativeQuery = true)
    int insertClassSubmissionIfAbsent(@Param("schoolId") Long schoolId, @Param("sessionId") Long sessionId,
                                      @Param("classId") Long classId, @Param("date") LocalDate date,
                                      @Param("userId") String userId, @Param("now") LocalDateTime now);

    /** A class's (exact section's) submissions in a date range, most recent first. */
    @Query("SELECT s FROM AttendanceSession s WHERE s.schoolId = :schoolId AND s.classId = :classId "
            + "AND ((:sectionId IS NULL AND s.sectionId IS NULL) OR s.sectionId = :sectionId) "
            + "AND s.attendanceDate >= :from AND s.attendanceDate <= :to ORDER BY s.attendanceDate DESC")
    List<AttendanceSession> findForScopeBetweenDesc(@Param("schoolId") Long schoolId, @Param("classId") Long classId,
                                                   @Param("sectionId") Long sectionId,
                                                   @Param("from") LocalDate from, @Param("to") LocalDate to);

    List<AttendanceSession> findBySchoolIdAndAttendanceDate(Long schoolId, LocalDate date);

    /** Backs SectionService#deleteSection's pre-check: a section with attendance history is historical fact. */
    boolean existsBySchoolIdAndSectionId(Long schoolId, Long sectionId);
}
