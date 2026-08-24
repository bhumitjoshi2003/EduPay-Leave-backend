package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.TeacherAttendanceSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;

public interface TeacherAttendanceScheduleRepository extends JpaRepository<TeacherAttendanceSchedule, Long> {
    List<TeacherAttendanceSchedule> findByTeacherIdAndSchoolIdOrderByEffectiveFromAsc(String teacherId, Long schoolId);

    @Query("select s from TeacherAttendanceSchedule s where s.schoolId = :schoolId " +
           "and s.effectiveFrom <= :end and (s.effectiveTo is null or s.effectiveTo >= :start) " +
           "order by s.teacherId, s.effectiveFrom")
    List<TeacherAttendanceSchedule> findOverlapping(@Param("schoolId") Long schoolId,
                                                     @Param("start") LocalDate start,
                                                     @Param("end") LocalDate end);
}

