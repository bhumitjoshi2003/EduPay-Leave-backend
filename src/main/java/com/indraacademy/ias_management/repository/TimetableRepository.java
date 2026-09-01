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

    // ── Slot occupants (section-specific) — used by TimetableValidationService to check
    //    whether a candidate row may join an existing slot (same or matching simultaneousGroup)
    //    or must be rejected as a conflict. ───────────────────────────────────────────────
    List<TimetableEntry> findByClassNameAndSectionIdAndDayAndPeriodNumberAndSchoolId(String className, Long sectionId, Day day, Integer periodNumber, Long schoolId);
    List<TimetableEntry> findByClassNameAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(String className, Day day, Integer periodNumber, Long schoolId);

    // ── A teacher's schedule for one day — used for cross-class time-overlap conflict checks.
    List<TimetableEntry> findByTeacherIdAndDayAndSchoolId(String teacherId, Day day, Long schoolId);

    List<TimetableEntry> findBySchoolId(Long schoolId);
}
