package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.AttendanceStatus;
import com.indraacademy.ias_management.entity.StudentAttendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface StudentAttendanceRepository extends JpaRepository<StudentAttendance, Long> {

    List<StudentAttendance> findByAttendanceSessionId(Long attendanceSessionId);

    List<StudentAttendance> findByAttendanceSessionIdIn(Collection<Long> attendanceSessionIds);

    String ROW = "SELECT new com.indraacademy.ias_management.repository.AttendanceRow(sa.id, s.schoolId, sa.studentId, "
            + "s.attendanceDate, sa.status, s.classId, s.sectionId) FROM StudentAttendance sa JOIN sa.attendanceSession s ";

    /** One student's rows in a date range, oldest first. */
    @Query(ROW + "WHERE s.schoolId = :schoolId AND sa.studentId = :studentId "
            + "AND s.attendanceDate >= :from AND s.attendanceDate <= :to ORDER BY s.attendanceDate ASC")
    List<AttendanceRow> findStudentRows(@Param("schoolId") Long schoolId, @Param("studentId") String studentId,
                                        @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** One student's ABSENT rows in a date range (the unapplied-absence fee's candidates). */
    @Query(ROW + "WHERE s.schoolId = :schoolId AND sa.studentId = :studentId AND sa.status = :status "
            + "AND s.attendanceDate >= :from AND s.attendanceDate <= :to ORDER BY s.attendanceDate ASC")
    List<AttendanceRow> findStudentRowsWithStatus(@Param("schoolId") Long schoolId, @Param("studentId") String studentId,
                                                  @Param("status") AttendanceStatus status,
                                                  @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Rows with one status on the given dates across every school (absence emails). */
    @Query(ROW + "WHERE sa.status = :status AND s.attendanceDate IN :dates")
    List<AttendanceRow> findRowsWithStatusOnDates(@Param("status") AttendanceStatus status,
                                                  @Param("dates") Collection<LocalDate> dates);

    /** A school's rows on one date (today's attendance rate). */
    @Query(ROW + "WHERE s.schoolId = :schoolId AND s.attendanceDate = :date")
    List<AttendanceRow> findSchoolRowsOnDate(@Param("schoolId") Long schoolId, @Param("date") LocalDate date);

    /**
     * Per-student status counts in a date range: a whole school (classId null), one class
     * (sectionId null = every section), or one section.
     */
    @Query("SELECT new com.indraacademy.ias_management.repository.AttendanceStatusCount(sa.studentId, sa.status, COUNT(sa)) "
            + "FROM StudentAttendance sa JOIN sa.attendanceSession s WHERE s.schoolId = :schoolId "
            + "AND (:classId IS NULL OR s.classId = :classId) AND (:sectionId IS NULL OR s.sectionId = :sectionId) "
            + "AND s.attendanceDate >= :from AND s.attendanceDate <= :to GROUP BY sa.studentId, sa.status")
    List<AttendanceStatusCount> countByStudentAndStatus(@Param("schoolId") Long schoolId, @Param("classId") Long classId,
                                                        @Param("sectionId") Long sectionId,
                                                        @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** ABSENT rows in the same scope as {@link #countByStudentAndStatus} (for approved-leave matching). */
    @Query(ROW + "WHERE s.schoolId = :schoolId AND sa.status = com.indraacademy.ias_management.entity.AttendanceStatus.ABSENT "
            + "AND (:classId IS NULL OR s.classId = :classId) AND (:sectionId IS NULL OR s.sectionId = :sectionId) "
            + "AND s.attendanceDate >= :from AND s.attendanceDate <= :to")
    List<AttendanceRow> findAbsentRows(@Param("schoolId") Long schoolId, @Param("classId") Long classId,
                                       @Param("sectionId") Long sectionId,
                                       @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Each class a student was marked in within a range and their last date there. */
    @Query("SELECT new com.indraacademy.ias_management.repository.StudentLatestClass(sa.studentId, s.classId, MAX(s.attendanceDate)) "
            + "FROM StudentAttendance sa JOIN sa.attendanceSession s WHERE s.schoolId = :schoolId "
            + "AND s.attendanceDate >= :from AND s.attendanceDate <= :to GROUP BY sa.studentId, s.classId")
    List<StudentLatestClass> findStudentClasses(@Param("schoolId") Long schoolId,
                                                @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Every row in a school in a date range, optionally for one class (dashboard class rates and trends). */
    @Query(ROW + "WHERE s.schoolId = :schoolId AND (:classId IS NULL OR s.classId = :classId) "
            + "AND s.attendanceDate >= :from AND s.attendanceDate <= :to")
    List<AttendanceRow> findSchoolRows(@Param("schoolId") Long schoolId, @Param("classId") Long classId,
                                       @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Removes a hard-deleted student's attendance within one school. */
    @Modifying
    @Query("DELETE FROM StudentAttendance sa WHERE sa.studentId = :studentId AND sa.attendanceSession.id IN "
            + "(SELECT s.id FROM AttendanceSession s WHERE s.schoolId = :schoolId)")
    int deleteByStudentIdAndSchoolId(@Param("studentId") String studentId, @Param("schoolId") Long schoolId);
}
