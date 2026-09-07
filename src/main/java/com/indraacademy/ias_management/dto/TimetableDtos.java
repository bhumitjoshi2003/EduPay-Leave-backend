package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.Day;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class TimetableDtos {

    /**
     * Phase F3: the API no longer accepts the {@code TimetableEntry} JPA entity directly as a
     * request body. Deliberately carries only canonical IDs — no className/sectionName field
     * exists here at all, so the server always derives and snapshots those from the referenced
     * {@code classId}/{@code sectionId} rather than trusting a client-supplied string (see
     * TimetableService). {@code academicSessionId} is required for ADMIN/SUPER_ADMIN callers;
     * for a TEACHER caller it is ignored and the server-resolved current session is used instead
     * — a teacher can never target an arbitrary historical or future session.
     */
    public record TimetableEntryRequest(
            Long academicSessionId,
            @NotNull(message = "classId is required.") Long classId,
            Long sectionId,
            @NotNull(message = "Day is required.") Day day,
            @NotNull(message = "Period number is required.") @Positive Integer periodNumber,
            @NotBlank(message = "Start time is required.") String startTime,
            @NotBlank(message = "End time is required.") String endTime,
            @NotBlank(message = "Subject is required.") String subjectName,
            String teacherId
    ) {}

    /**
     * Body for POST /api/timetable/{id}/simultaneous. Deliberately carries only what actually
     * differs between the two subjects — class/section/day/period/time are inherited from the
     * existing entry server-side (see TimetableService#addSimultaneous), so the client can never
     * send a mismatched slot, and the simultaneousGroup tag is generated/reused automatically
     * rather than typed by the admin. {@code academicSessionId} must match the existing entry's
     * actual session (fail closed otherwise) — see the class-level note on
     * {@link TimetableEntryRequest} for why a TEACHER caller's value is ignored instead.
     */
    public record AddSimultaneousRequest(
            Long academicSessionId,
            @NotBlank(message = "Subject name is required.") String subjectName,
            @NotBlank(message = "Please select a teacher.") String teacherId
    ) {}

    /**
     * Body for POST /api/timetable/copy-session — an explicit ADMIN action, never implicit or
     * automatic. The copy itself is always additive/idempotent: it never deletes, overwrites, or
     * replaces an existing target row (see TimetableSessionCopyWorker) — an exact match is
     * reported {@code ALREADY_COPIED}, a genuinely different occupant is reported {@code CONFLICT}
     * and left untouched, and only a truly empty, compatible slot is actually written.
     * {@code confirmCurrentTarget} must be true when {@code targetAcademicSessionId} is the
     * school's current session — that's the one case worth a deliberate second thought, since the
     * target is a live, operational timetable, not because the copy would destroy anything there.
     */
    public record CopySessionRequest(
            @NotNull(message = "sourceAcademicSessionId is required.") Long sourceAcademicSessionId,
            @NotNull(message = "targetAcademicSessionId is required.") Long targetAcademicSessionId,
            boolean confirmCurrentTarget
    ) {}

    /** One source row's copy outcome. */
    public record CopyRowResult(Long sourceEntryId, Long targetEntryId, String outcome, String reason) {}

    public record CopySessionResult(
            Long sourceAcademicSessionId,
            Long targetAcademicSessionId,
            int scanned,
            int copied,
            int alreadyCopied,
            int skippedIneligibleTeacher,
            int skippedInvalidClass,
            int skippedInvalidSection,
            int conflicts,
            int failures,
            java.util.List<CopyRowResult> details
    ) {}

    private TimetableDtos() {}
}
