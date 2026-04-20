package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Day;
import com.indraacademy.ias_management.entity.TimetableEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TimetableRepository extends JpaRepository<TimetableEntry, Long> {

    List<TimetableEntry> findByClassNameOrderByDayAscPeriodNumberAsc(String className);

    List<TimetableEntry> findByTeacherIdOrderByDayAscPeriodNumberAsc(String teacherId);

    boolean existsByClassNameAndDayAndPeriodNumber(String className, Day day, Integer periodNumber);

    boolean existsByClassNameAndDayAndPeriodNumberAndIdNot(String className, Day day, Integer periodNumber, Long id);
}
