package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Day;
import com.indraacademy.ias_management.entity.TimetableEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TimetableRepository extends JpaRepository<TimetableEntry, Long> {

    // ── Fetch by class (all sections) ──────────────────────────────────────
    List<TimetableEntry> findByClassNameAndSchoolIdOrderByDayAscPeriodNumberAsc(String className, Long schoolId);

    // ── Fetch by class + specific section ─────────────────────────────────
    List<TimetableEntry> findByClassNameAndSectionIdAndSchoolIdOrderByDayAscPeriodNumberAsc(String className, Long sectionId, Long schoolId);

    // ── Fetch by class with no section (class-wide entries) ────────────────
    List<TimetableEntry> findByClassNameAndSectionIdIsNullAndSchoolIdOrderByDayAscPeriodNumberAsc(String className, Long schoolId);

    // ── Teacher schedule ───────────────────────────────────────────────────
    List<TimetableEntry> findByTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc(String teacherId, Long schoolId);

    // ── Duplicate-slot checks (section-specific) ───────────────────────────
    boolean existsByClassNameAndSectionIdAndDayAndPeriodNumberAndSchoolId(String className, Long sectionId, Day day, Integer periodNumber, Long schoolId);
    boolean existsByClassNameAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(String className, Day day, Integer periodNumber, Long schoolId);

    boolean existsByClassNameAndSectionIdAndDayAndPeriodNumberAndSchoolIdAndIdNot(String className, Long sectionId, Day day, Integer periodNumber, Long schoolId, Long id);
    boolean existsByClassNameAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolIdAndIdNot(String className, Day day, Integer periodNumber, Long schoolId, Long id);

    List<TimetableEntry> findBySchoolId(Long schoolId);
}
