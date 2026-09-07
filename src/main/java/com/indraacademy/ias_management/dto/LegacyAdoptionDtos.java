package com.indraacademy.ias_management.dto;

import java.util.List;

/**
 * Phase F5A: controlled, explicit adoption of PROD legacy data into the F2-F4.1 session-scoped
 * model — {@code timetable_entry} rows with {@code academic_session_id = NULL} into canonical
 * session/class/section ids, and current live {@code Teacher.classTeacher}/
 * {@code classTeacherSectionId} assignments into {@code class_teacher_responsibility} rows.
 * Every candidate is classified before any write is attempted; {@code dryRun = true} performs
 * zero writes and computes the entire plan from reads only. See
 * {@link com.indraacademy.ias_management.service.LegacyTimetableAdoptionService} and
 * {@link com.indraacademy.ias_management.service.LegacyResponsibilityAdoptionService} for exactly
 * how each outcome is decided.
 */
public class LegacyAdoptionDtos {

    /** {@code ALREADY_ADOPTED} means this exact row/assignment already has a canonical
     *  counterpart matching the requested target — running adoption again is a no-op for it
     *  (idempotency). {@code SKIPPED} is reserved for rows structurally out of scope for this
     *  target (e.g. a timetable row that already belongs to a DIFFERENT, non-target session). */
    public enum AdoptionOutcome {
        SAFE,
        REQUIRES_ADMIN_CONFIRMATION,
        INVALID_OR_CONFLICTING,
        ALREADY_ADOPTED,
        SKIPPED
    }

    /** One legacy timetable row's classification. {@code simultaneousGroupConcern} is
     *  deliberately independent of {@code outcome} — a row can be technically {@code SAFE} to
     *  adopt while still carrying a semantic tag concern an admin should review (see class
     *  Javadoc on {@code LegacyTimetableAdoptionService} for why a questionable tag never by
     *  itself blocks adoption). */
    public record TimetableCandidate(
            Long timetableEntryId,
            String legacyClassName,
            String legacySectionName,
            String day,
            Integer periodNumber,
            String startTime,
            String endTime,
            String subjectName,
            String teacherId,
            String simultaneousGroup,
            AdoptionOutcome outcome,
            String reason,
            Long resolvedClassId,
            Long resolvedSectionId,
            boolean simultaneousGroupConcern,
            String simultaneousGroupConcernReason
    ) {}

    public record TimetableAdoptionReport(
            Long schoolId,
            Long academicSessionId,
            boolean dryRun,
            int scanned,
            int safe,
            int requiresAdminConfirmation,
            int invalidOrConflicting,
            int alreadyAdopted,
            int skipped,
            int adopted,
            int unresolvedClassMappings,
            int unresolvedSectionMappings,
            int invalidOrIneligibleTeachers,
            int slotConflicts,
            int teacherOverlaps,
            int simultaneousGroupConcerns,
            List<TimetableCandidate> details
    ) {}

    /** One live {@code Teacher.classTeacher} assignment's classification against the target
     *  session's {@code class_teacher_responsibility} configuration. */
    public record ResponsibilityCandidate(
            String teacherId,
            String teacherName,
            String legacyClassName,
            Long legacyClassTeacherSectionId,
            AdoptionOutcome outcome,
            String reason,
            Long resolvedClassId,
            Long resolvedSectionId
    ) {}

    public record ResponsibilityAdoptionReport(
            Long schoolId,
            Long academicSessionId,
            boolean dryRun,
            int scanned,
            int safe,
            int requiresAdminConfirmation,
            int invalidOrConflicting,
            int alreadyAdopted,
            int adopted,
            List<ResponsibilityCandidate> details
    ) {}

    /** A deterministic, content-based snapshot of everything real adoption could possibly touch
     *  (or must never touch) — captured before and after a dry-run (or, in a future real run,
     *  before/after each transaction) to prove non-mutation by equality rather than by trusting
     *  the code path taken. Uses the same SHA-256-of-canonical-sorted-tuples technique as
     *  {@code ClassTeacherActivationService}'s configuration fingerprint, for the same reason:
     *  a pure content comparison, not a timestamp or row-count alone, is what actually proves
     *  "nothing changed" under concurrent activity. */
    public record InvariantSnapshot(
            Long schoolId,
            Long academicSessionId,
            long timetableRowCount,
            long timetableNullSessionCount,
            String timetableContentChecksum,
            long teacherClassTeacherAssignedCount,
            String teacherClassTeacherChecksum,
            long responsibilityCountForSession,
            String responsibilityChecksumForSession,
            long activationProvenanceRowsForSession,
            String activationProvenanceChecksumForSession
    ) {}

    private LegacyAdoptionDtos() {}
}
