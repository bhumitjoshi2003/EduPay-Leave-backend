package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.TeacherAttendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TeacherAttendanceRepository extends JpaRepository<TeacherAttendance, Long> {

    interface LastTeacherCheckIn {
        String getTeacherId();
        LocalDateTime getLastAttendanceAt();
    }

    Optional<TeacherAttendance> findByTeacherIdAndDateAndSchoolId(String teacherId, LocalDate date, Long schoolId);

    List<TeacherAttendance> findBySchoolIdAndDate(Long schoolId, LocalDate date);

    List<TeacherAttendance> findByTeacherIdAndSchoolIdAndDateBetweenOrderByDateAsc(
            String teacherId, Long schoolId, LocalDate start, LocalDate end);

    List<TeacherAttendance> findBySchoolIdAndDateBetweenOrderByDateAsc(
            Long schoolId, LocalDate start, LocalDate end);

    @Query("SELECT a.teacherId AS teacherId, MAX(a.checkInTime) AS lastAttendanceAt " +
            "FROM TeacherAttendance a WHERE a.schoolId = :schoolId AND a.teacherId IN :teacherIds " +
            "AND a.markedByAdmin = false AND a.checkInTime IS NOT NULL GROUP BY a.teacherId")
    List<LastTeacherCheckIn> findLastSelfCheckInBySchoolIdAndTeacherIds(
            @Param("schoolId") Long schoolId, @Param("teacherIds") Collection<String> teacherIds);
}
