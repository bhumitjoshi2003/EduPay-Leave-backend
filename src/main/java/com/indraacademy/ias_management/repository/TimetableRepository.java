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

    // ── Phase F3: session-scoped authority. Every method below binds a non-null
    //    academicSessionId, so SQL equality naturally excludes every legacy row where that
    //    column is NULL — no explicit "IS NOT NULL" filter is needed for that exclusion to hold,
    //    and none of these methods may ever be called with a null session id. ─────────────────

    // Operational/admin reads, scoped to one explicit session (current, resolved server-side for
    // operational reads; admin-chosen, including historical/future, for explicit admin reads).
    List<TimetableEntry> findByAcademicSessionIdAndClassNameAndSchoolIdOrderByDayAscPeriodNumberAsc(
            Long academicSessionId, String className, Long schoolId);
    List<TimetableEntry> findByAcademicSessionIdAndClassNameAndSectionIdAndSchoolIdOrderByDayAscPeriodNumberAsc(
            Long academicSessionId, String className, Long sectionId, Long schoolId);
    List<TimetableEntry> findByAcademicSessionIdAndTeacherIdAndSchoolIdOrderByDayAscPeriodNumberAsc(
            Long academicSessionId, String teacherId, Long schoolId);
    List<TimetableEntry> findByAcademicSessionIdAndTeacherIdAndSchoolId(
            Long academicSessionId, String teacherId, Long schoolId);

    // Slot occupants keyed by canonical classId (not the className string) — used by the
    // session-scoped validator for new/updated entries.
    List<TimetableEntry> findByAcademicSessionIdAndClassIdAndSectionIdAndDayAndPeriodNumberAndSchoolId(
            Long academicSessionId, Long classId, Long sectionId, Day day, Integer periodNumber, Long schoolId);
    List<TimetableEntry> findByAcademicSessionIdAndClassIdAndSectionIdIsNullAndDayAndPeriodNumberAndSchoolId(
            Long academicSessionId, Long classId, Day day, Integer periodNumber, Long schoolId);

    // A teacher's schedule for one day, within one session — the session-scoped teacher-overlap
    // check ("same weekly teacher/time slot in two different sessions is valid").
    List<TimetableEntry> findByAcademicSessionIdAndTeacherIdAndDayAndSchoolId(
            Long academicSessionId, String teacherId, Day day, Long schoolId);

    List<TimetableEntry> findByAcademicSessionIdAndSchoolId(Long academicSessionId, Long schoolId);

    /** Phase F5B.1: backs {@code SectionService#deleteSection}'s explicit pre-check — V55's
     *  {@code fk_timetable_entry_section} (ON DELETE RESTRICT) would otherwise reject the delete
     *  with a raw, unhandled constraint violation instead of a clean application error. */
    boolean existsBySchoolIdAndSectionId(Long schoolId, Long sectionId);
}
