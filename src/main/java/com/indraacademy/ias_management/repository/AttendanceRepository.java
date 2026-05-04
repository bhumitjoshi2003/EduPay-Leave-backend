package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    @Transactional
    void deleteByDateAndClassNameAndSchoolId(LocalDate date, String className, Long schoolId);

    List<Attendance> findByDateAndClassNameAndSchoolId(LocalDate date, String className, Long schoolId);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.studentId = :studentId AND a.schoolId = :schoolId AND EXTRACT(YEAR FROM a.date) = :year AND EXTRACT(MONTH FROM a.date) = :month")
    long countAbsences(@Param("studentId") String studentId, @Param("schoolId") Long schoolId, @Param("year") int year, @Param("month") int month);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.studentId = :studentId AND a.schoolId = :schoolId AND a.chargePaid = false " +
            "AND ((EXTRACT(YEAR FROM a.date) = :startYear AND EXTRACT(MONTH FROM a.date) >= 4) " +
            "OR (EXTRACT(YEAR FROM a.date) = :endYear AND EXTRACT(MONTH FROM a.date) <= 3))")
    long countUnappliedLeavesForAcademicYear(@Param("studentId") String studentId, @Param("schoolId") Long schoolId, @Param("startYear") int startYear, @Param("endYear") int endYear);

    @Transactional
    @Modifying
    @Query("UPDATE Attendance a SET a.chargePaid = true WHERE a.studentId = :studentId AND a.schoolId = :schoolId " +
            "AND a.date >= :startDate AND a.date <= :endDate AND a.chargePaid = false")
    void updateChargePaidForSession(@Param("studentId") String studentId, @Param("schoolId") Long schoolId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.studentId = :studentId AND a.schoolId = :schoolId AND EXTRACT(YEAR FROM a.date) = :year AND EXTRACT(MONTH FROM a.date) = :month AND a.date < :joinDate")
    long countAbsencesBeforeJoin(@Param("studentId") String studentId, @Param("schoolId") Long schoolId, @Param("year") int year, @Param("month") int month, @Param("joinDate") LocalDate joinDate);

    // Used by scheduler — no schoolId filter (runs platform-wide)
    List<Attendance> findByDate(LocalDate today);

    List<Attendance> findByDateAndSchoolId(LocalDate date, Long schoolId);

    @Query("SELECT COUNT(DISTINCT a.date) FROM Attendance a WHERE a.className = :className AND a.schoolId = :schoolId AND EXTRACT(YEAR FROM a.date) = :year AND EXTRACT(MONTH FROM a.date) = :month")
    long countWorkingDaysForClass(@Param("className") String className, @Param("schoolId") Long schoolId, @Param("year") int year, @Param("month") int month);

    @Query("SELECT COUNT(DISTINCT a.date) FROM Attendance a WHERE a.className = :className AND a.schoolId = :schoolId AND EXTRACT(YEAR FROM a.date) = :year AND EXTRACT(MONTH FROM a.date) = :month AND a.date < :joinDate")
    long countWorkingDaysBeforeJoin(@Param("className") String className, @Param("schoolId") Long schoolId, @Param("year") int year, @Param("month") int month, @Param("joinDate") LocalDate joinDate);

    @Query("SELECT COUNT(DISTINCT a.date) FROM Attendance a WHERE a.className = :className AND a.schoolId = :schoolId AND EXTRACT(YEAR FROM a.date) = :year AND EXTRACT(MONTH FROM a.date) = :month AND a.date > :leaveDate")
    long countWorkingDaysAfterLeave(@Param("className") String className, @Param("schoolId") Long schoolId, @Param("year") int year, @Param("month") int month, @Param("leaveDate") LocalDate leaveDate);

    @Query("SELECT a FROM Attendance a WHERE a.studentId = :studentId AND a.schoolId = :schoolId " +
            "AND a.className = :className AND a.date >= :startDate AND a.date <= :endDate")
    List<Attendance> findByStudentIdAndClassNameAndDateRange(
            @Param("studentId") String studentId,
            @Param("schoolId") Long schoolId,
            @Param("className") String className,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT COUNT(DISTINCT a.date) FROM Attendance a WHERE a.className = :className AND a.schoolId = :schoolId AND a.date >= :startDate AND a.date <= :endDate")
    long countDistinctWorkingDays(@Param("className") String className,
                                  @Param("schoolId") Long schoolId,
                                  @Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate);

    long countByStudentIdAndSchoolIdAndDateBetween(String studentId, Long schoolId, LocalDate startDate, LocalDate endDate);

    List<Attendance> findByStudentIdAndSchoolIdAndDateBetween(String studentId, Long schoolId, LocalDate startDate, LocalDate endDate);

    List<Attendance> findByClassNameAndSchoolIdAndDateBetween(String className, Long schoolId, LocalDate startDate, LocalDate endDate);
}
