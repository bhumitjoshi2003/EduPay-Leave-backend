package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.TeacherSubstitution;
import com.indraacademy.ias_management.entity.TeacherSubstitutionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TeacherSubstitutionRepository extends JpaRepository<TeacherSubstitution, Long> {
    List<TeacherSubstitution> findBySchoolIdAndDateAndStatus(Long schoolId, LocalDate date, TeacherSubstitutionStatus status);
    List<TeacherSubstitution> findBySchoolIdAndSubstituteTeacherIdAndDateAndStatusOrderByPeriodNumberAsc(
            Long schoolId, String teacherId, LocalDate date, TeacherSubstitutionStatus status);
    Optional<TeacherSubstitution> findBySchoolIdAndDateAndTimetableEntryIdAndStatus(
            Long schoolId, LocalDate date, Long timetableEntryId, TeacherSubstitutionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TeacherSubstitution s where s.id = :id and s.schoolId = :schoolId")
    Optional<TeacherSubstitution> lockByIdAndSchoolId(@Param("id") Long id, @Param("schoolId") Long schoolId);
}
