package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Day;
import com.indraacademy.ias_management.entity.TimetableEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TimetableRepository extends JpaRepository<TimetableEntry, Long> {

    List<TimetableEntry> findByClassNameAndSchoolIdOrderByDayAscPeriodNumberAsc(String className, Long schoolId);

    List<TimetableEntry> findByTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc(String teacherId, Long schoolId);

    boolean existsByClassNameAndDayAndPeriodNumberAndSchoolId(String className, Day day, Integer periodNumber, Long schoolId);

    boolean existsByClassNameAndDayAndPeriodNumberAndSchoolIdAndIdNot(String className, Day day, Integer periodNumber, Long schoolId, Long id);
}
