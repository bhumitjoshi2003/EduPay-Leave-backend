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
    void deleteByDateAndClassName(LocalDate date, String className);

    List<Attendance> findByDateAndClassName(LocalDate date, String className);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.studentId = :studentId AND EXTRACT(YEAR FROM a.date) = :year AND EXTRACT(MONTH FROM a.date) = :month")
    long countAbsences(@Param("studentId") String studentId, @Param("year") int year, @Param("month") int month);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.studentId = :studentId AND a.chargePaid = false " +
            "AND ((EXTRACT(YEAR FROM a.date) = :startYear AND EXTRACT(MONTH FROM a.date) >= 4) " +
            "OR (EXTRACT(YEAR FROM a.date) = :endYear AND EXTRACT(MONTH FROM a.date) <= 3))")
    long countUnappliedLeavesForAcademicYear(@Param("studentId") String studentId, @Param("startYear") int startYear, @Param("endYear") int endYear);

    @Transactional
    @Modifying
    @Query("UPDATE Attendance a SET a.chargePaid = true WHERE a.studentId = :studentId " +
            "AND a.date >= :startDate AND a.date <= :endDate AND a.chargePaid = false")
    void updateChargePaidForSession(@Param("studentId") String studentId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.studentId = :studentId AND EXTRACT(YEAR FROM a.date) = :year AND EXTRACT(MONTH FROM a.date) = :month AND a.date < :joinDate")
    long countAbsencesBeforeJoin(@Param("studentId") String studentId, @Param("year") int year, @Param("month") int month, @Param("joinDate") LocalDate joinDate);

    List<Attendance> findByDate(LocalDate today);

    @Query("SELECT COUNT(DISTINCT a.date) FROM Attendance a WHERE a.className = :className AND EXTRACT(YEAR FROM a.date) = :year AND EXTRACT(MONTH FROM a.date) = :month")
    long countWorkingDaysForClass(@Param("className") String className, @Param("year") int year, @Param("month") int month);

    @Query("SELECT COUNT(DISTINCT a.date) FROM Attendance a WHERE a.className = :className AND EXTRACT(YEAR FROM a.date) = :year AND EXTRACT(MONTH FROM a.date) = :month AND a.date < :joinDate")
    long countWorkingDaysBeforeJoin(@Param("className") String className, @Param("year") int year, @Param("month") int month, @Param("joinDate") LocalDate joinDate);

    @Query("SELECT COUNT(DISTINCT a.date) FROM Attendance a WHERE a.className = :className AND EXTRACT(YEAR FROM a.date) = :year AND EXTRACT(MONTH FROM a.date) = :month AND a.date > :leaveDate")
    long countWorkingDaysAfterLeave(@Param("className") String className, @Param("year") int year, @Param("month") int month, @Param("leaveDate") LocalDate leaveDate);
}