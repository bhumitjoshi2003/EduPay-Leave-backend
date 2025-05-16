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
    void deleteByAbsentDateAndClassName(LocalDate absentDate, String className);

    List<Attendance> findByAbsentDateAndClassName(LocalDate absentDate, String className);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.studentId = :studentId AND FUNCTION('YEAR', a.absentDate) = :year AND FUNCTION('MONTH', a.absentDate) = :month")
    long countAbsences(@Param("studentId") String studentId, @Param("year") int year, @Param("month") int month);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.studentId = :studentId AND a.chargePaid = false " +
            "AND ((FUNCTION('YEAR', a.absentDate) = :startYear AND FUNCTION('MONTH', a.absentDate) >= 4) " +
            "OR (FUNCTION('YEAR', a.absentDate) = :endYear AND FUNCTION('MONTH', a.absentDate) <= 3))")
    long countUnappliedLeavesForAcademicYear(@Param("studentId") String studentId, @Param("startYear") int startYear, @Param("endYear") int endYear);

    @Transactional
    @Modifying
    @Query("UPDATE Attendance a SET a.chargePaid = true WHERE a.studentId = :studentId " +
            "AND a.absentDate >= :startDate AND a.absentDate <= :endDate AND a.chargePaid = false")
    void updateChargePaidForSession(@Param("studentId") String studentId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.studentId = :studentId AND FUNCTION('YEAR', a.absentDate) = :year AND FUNCTION('MONTH', a.absentDate) = :month AND a.absentDate < :joinDate")
    long countAbsencesBeforeJoin(@Param("studentId") String studentId, @Param("year") int year, @Param("month") int month, @Param("joinDate") LocalDate joinDate
    );

    List<Attendance> findByAbsentDate(LocalDate today);
}