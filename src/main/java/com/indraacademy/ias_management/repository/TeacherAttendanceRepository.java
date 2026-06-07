package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.TeacherAttendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TeacherAttendanceRepository extends JpaRepository<TeacherAttendance, Long> {

    @Query("SELECT COUNT(DISTINCT ta.date) FROM TeacherAttendance ta WHERE ta.schoolId = :schoolId AND ta.date BETWEEN :start AND :end")
    int countDistinctWorkingDays(@Param("schoolId") Long schoolId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    Optional<TeacherAttendance> findByTeacherIdAndDateAndSchoolId(String teacherId, LocalDate date, Long schoolId);

    List<TeacherAttendance> findBySchoolIdAndDate(Long schoolId, LocalDate date);

    List<TeacherAttendance> findByTeacherIdAndSchoolIdAndDateBetweenOrderByDateAsc(
            String teacherId, Long schoolId, LocalDate start, LocalDate end);

    List<TeacherAttendance> findBySchoolIdAndDateBetweenOrderByDateAsc(
            Long schoolId, LocalDate start, LocalDate end);
}
