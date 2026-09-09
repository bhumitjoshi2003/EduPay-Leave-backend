package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.TimetableDtos.CopyRowResult;
import com.indraacademy.ias_management.dto.TimetableDtos.CopySessionResult;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.TimetableRepository;
import com.indraacademy.ias_management.service.TimetableSessionCopyWorker.Evaluation;
import com.indraacademy.ias_management.service.TimetableSessionCopyWorker.Outcome;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit, ADMIN-triggered copy of one AcademicSession's timetable configuration into another —
 * never automatic, never a fallback. Deliberately has no surrounding transaction of its own: each
 * source row is evaluated/copied in the worker's independent {@code REQUIRES_NEW} transaction
 * (see {@link TimetableSessionCopyWorker}), so one bad row cannot roll back rows already copied.
 *
 * <p>Never copies {@code Teacher.classTeacher}/{@code classTeacherSectionId},
 * {@code class_teacher_responsibility}, or {@code teacher_class_grant} — those belong to a later
 * phase; this service touches {@code timetable_entry} rows only.
 */
@Service
public class TimetableSessionCopyService {

    private static final Logger log = LoggerFactory.getLogger(TimetableSessionCopyService.class);

    @Autowired private TimetableRepository timetableRepository;
    @Autowired private TimetableSessionAccessService sessionAccess;
    @Autowired private TimetableSessionCopyWorker worker;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;

    public CopySessionResult copy(Long sourceSessionId, Long targetSessionId, boolean confirmCurrentTarget,
            HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();

        if (sourceSessionId == null || targetSessionId == null) {
            throw new IllegalArgumentException("sourceAcademicSessionId and targetAcademicSessionId are required.");
        }
        if (sourceSessionId.equals(targetSessionId)) {
            throw new IllegalArgumentException("sourceAcademicSessionId and targetAcademicSessionId must differ.");
        }

        // Both sessions must belong to the authenticated school — a cross-school id fails closed
        // exactly like every other tenant-scoped lookup in this codebase.
        AcademicSession source = sessionAccess.requireOwnedSession(schoolId, sourceSessionId);
        AcademicSession target = sessionAccess.requireOwnedSession(schoolId, targetSessionId);

        // Target must be writable at all (not historical) — same rule as any other timetable write.
        sessionAccess.requireWritable(target);

        // Copying INTO the current session touches a live, operational timetable — the copy
        // itself is additive/idempotent (never destructive; see TimetableSessionCopyWorker), but
        // an admin should still confirm deliberately before adding rows to what's actually being
        // taught from right now, rather than have a routine "prepare next year" action land there
        // by mistake.
        if (target.isCurrent() && !confirmCurrentTarget) {
            throw new IllegalStateException(
                    "Target session " + target.getLabel() + " is the current session. "
                            + "Pass confirmCurrentTarget=true to copy into it deliberately.");
        }

        List<TimetableEntry> sourceRows = timetableRepository.findByAcademicSessionIdAndSchoolId(sourceSessionId, schoolId);

        int copied = 0, alreadyCopied = 0, skippedTeacher = 0, skippedClass = 0, skippedSection = 0, failures = 0;
        List<CopyRowResult> details = new ArrayList<>(sourceRows.size());

        for (TimetableEntry row : sourceRows) {
            Evaluation evaluation;
            try {
                evaluation = worker.attempt(schoolId, row, targetSessionId);
            } catch (RuntimeException ex) {
                failures++;
                details.add(new CopyRowResult(row.getId(), null, Outcome.FAILURE.name(),
                        "Copy failed; inspect server logs"));
                log.error("Timetable session copy failed for source entryId={}, source={}, target={}",
                        row.getId(), sourceSessionId, targetSessionId, ex);
                continue;
            }

            switch (evaluation.outcome()) {
                case COPIED -> copied++;
                case ALREADY_COPIED -> alreadyCopied++;
                case SKIPPED_INELIGIBLE_TEACHER -> skippedTeacher++;
                case SKIPPED_INVALID_CLASS -> skippedClass++;
                case SKIPPED_INVALID_SECTION -> skippedSection++;
                case FAILURE -> failures++;
            }
            Long targetEntryId = evaluation.entry() != null ? evaluation.entry().getId() : null;
            details.add(new CopyRowResult(row.getId(), targetEntryId, evaluation.outcome().name(), evaluation.reason()));
        }

        CopySessionResult result = new CopySessionResult(
                sourceSessionId, targetSessionId, sourceRows.size(), copied, alreadyCopied,
                skippedTeacher, skippedClass, skippedSection, failures, List.copyOf(details));

        log.warn("Timetable session copy completed: source={}, target={}, scanned={}, copied={}, alreadyCopied={}, "
                        + "skippedIneligibleTeacher={}, skippedInvalidClass={}, skippedInvalidSection={}, failures={}",
                sourceSessionId, targetSessionId, result.scanned(), copied, alreadyCopied,
                skippedTeacher, skippedClass, skippedSection, failures);

        try {
            auditService.log(
                    securityUtil.getUsername(), securityUtil.getRole(), "COPY_TIMETABLE_SESSION",
                    "TimetableEntry", "SESSION:" + sourceSessionId + "->" + targetSessionId,
                    null, result.toString(), request.getRemoteAddr());
        } catch (RuntimeException ignored) {
            // Audit failure must never mask a completed copy result.
        }

        return result;
    }
}
