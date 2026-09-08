package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

public class ClassTeacherResponsibilityDtos {

    /** Phase F4.1: whether this session's CURRENT configuration has ever been explicitly
     *  applied — a separate claim from {@code inSync}, which only says live happens to match
     *  configured right now (possible by pure coincidence, e.g. right after a session switch,
     *  with nobody having activated anything). See ClassTeacherActivationService for exactly how
     *  each state is computed. */
    public enum ActivationState {
        /** No successful apply is on record for this (school, session) at all. */
        NEVER_APPLIED,
        /** A successful apply is on record, its configuration fingerprint still matches the
         *  current configuration, and live still matches configured. */
        APPLIED_IN_SYNC,
        /** A successful apply is on record, but the configuration has changed since (a
         *  responsibility was added/edited/removed) or the live projection no longer matches
         *  configured (e.g. a direct Teacher edit or an exit happened since). */
        APPLIED_BUT_DRIFTED
    }

    /** Deliberately carries only canonical IDs — no class/section name field exists, so the
     *  server always derives display names from the referenced ids (see
     *  ClassTeacherResponsibilityService). {@code teacherId} must resolve to an ACTIVE teacher
     *  in this school. */
    public record Request(
            @NotNull(message = "academicSessionId is required.") Long academicSessionId,
            @NotNull(message = "classId is required.") Long classId,
            Long sectionId,
            @NotBlank(message = "teacherId is required.") String teacherId
    ) {}

    /** One configured responsibility row, enriched with the CURRENT live projection for the same
     *  class/section so a caller can distinguish "what's configured" from "what's actually
     *  granting access right now" without this table itself ever being read by
     *  TeacherClassScopeService. {@code liveMatchesConfigured} is a point-in-time comparison, not
     *  a claim that this row was ever applied. */
    public record View(
            Long id,
            Long academicSessionId,
            Long classId,
            String className,
            Long sectionId,
            String sectionName,
            String configuredTeacherId,
            String configuredTeacherName,
            String liveTeacherId,
            String liveTeacherName,
            boolean liveMatchesConfigured
    ) {}

    public record CopyRequest(
            @NotNull(message = "sourceAcademicSessionId is required.") Long sourceAcademicSessionId,
            @NotNull(message = "targetAcademicSessionId is required.") Long targetAcademicSessionId
    ) {}

    public record CopyRowResult(Long sourceId, Long targetId, String outcome, String reason) {}

    public record CopyResult(
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
            List<CopyRowResult> details
    ) {}

    /** One class/section's activation outcome — used identically by preview (read-only) and the
     *  apply result (what actually happened), so a caller can diff them if desired. */
    public record ActivationRowResult(
            Long classId,
            String className,
            Long sectionId,
            String sectionName,
            String configuredTeacherId,
            String configuredTeacherName,
            String priorLiveTeacherId,
            String priorLiveTeacherName,
            String outcome,
            String reason
    ) {}

    /** Read-only — never writes. {@code inSync} is true only when applying would change nothing
     *  RIGHT NOW (no gains, no changes, no clearings) — it does NOT mean this configuration was
     *  ever explicitly activated (see {@code activationState} for that claim instead; do not
     *  treat one as proof of the other). {@code hasIssues} flags configuration problems
     *  (ineligible teacher, deleted class/section) that block a slot from ever going live until
     *  an admin fixes the underlying row, independent of sync state.
     *
     *  <p>{@code configuredCount} is the raw {@code class_teacher_responsibility} row count for
     *  this session, BEFORE any validity filtering — deliberately exposed so a caller (e.g. the
     *  "Make Current" confirmation) can distinguish "zero rows configured at all" from "rows
     *  configured but every one is ineligible/invalid" from "rows configured and valid, but they
     *  happen to already match live" without guessing from becomingLive/changing/clearing alone,
     *  which cannot tell those three cases apart on their own. */
    public record ActivationPreviewResult(
            Long academicSessionId,
            boolean inSync,
            ActivationState activationState,
            LocalDateTime lastAppliedAt,
            String lastAppliedBy,
            boolean hasIssues,
            int configuredCount,
            int becomingLive,
            int changing,
            int unchanged,
            int clearing,
            int ineligibleTeacher,
            int invalidClassOrSection,
            List<ActivationRowResult> details
    ) {}

    /** {@code activationState} here always reflects the state immediately AFTER this apply call
     *  completed (i.e. {@code APPLIED_IN_SYNC} on any successful call, including an idempotent
     *  re-run) — a failed apply throws before this result (or any provenance row) is ever
     *  produced. */
    public record ActivationApplyResult(
            Long academicSessionId,
            ActivationState activationState,
            LocalDateTime lastAppliedAt,
            String lastAppliedBy,
            int applied,
            int cleared,
            int unchanged,
            int ineligibleTeacher,
            int invalidClassOrSection,
            List<ActivationRowResult> details
    ) {}

    /** Result of the combined "make session current + activate its class-teacher
     *  responsibilities" lifecycle action. {@code activationPerformed=false} means the target
     *  was ALREADY the current session — a genuine no-op (no session-flag rewrite, no new
     *  activation event, no provenance touched) rather than a redundant re-apply; {@code
     *  activation} is null in that case. */
    public record SessionActivationOutcome(
            AcademicSessionDto session,
            boolean activationPerformed,
            ActivationApplyResult activation
    ) {}

    private ClassTeacherResponsibilityDtos() {}
}
