package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.CopyResult;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.CopyRowResult;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.ClassTeacherResponsibility;
import com.indraacademy.ias_management.repository.ClassTeacherResponsibilityRepository;
import com.indraacademy.ias_management.service.ClassTeacherResponsibilityCopyWorker.Evaluation;
import com.indraacademy.ias_management.service.ClassTeacherResponsibilityCopyWorker.Outcome;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit, ADMIN-triggered copy of one AcademicSession's class-teacher responsibility
 * configuration into another — never automatic. Additive/idempotent, exactly like
 * {@link TimetableSessionCopyService}: never deletes/overwrites an existing target row. Unlike
 * timetable copy, this operation does not require extra confirmation for a current target — the
 * copied rows are pure configuration and have no live effect until a separate, explicit
 * {@link ClassTeacherActivationService#apply} step, which is the actual authorization-affecting
 * action.
 *
 * <p>Never touches {@code Teacher.classTeacher}/{@code classTeacherSectionId}, {@code timetable_entry},
 * or {@code teacher_class_grant}.
 */
@Service
public class ClassTeacherResponsibilityCopyService {

    private static final Logger log = LoggerFactory.getLogger(ClassTeacherResponsibilityCopyService.class);

    @Autowired private ClassTeacherResponsibilityRepository responsibilityRepository;
    @Autowired private TimetableSessionAccessService sessionAccess;
    @Autowired private ClassTeacherResponsibilityCopyWorker worker;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;

    public CopyResult copy(Long sourceSessionId, Long targetSessionId, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();

        if (sourceSessionId == null || targetSessionId == null) {
            throw new IllegalArgumentException("sourceAcademicSessionId and targetAcademicSessionId are required.");
        }
        if (sourceSessionId.equals(targetSessionId)) {
            throw new IllegalArgumentException("sourceAcademicSessionId and targetAcademicSessionId must differ.");
        }

        AcademicSession source = sessionAccess.requireOwnedSession(schoolId, sourceSessionId);
        AcademicSession target = sessionAccess.requireOwnedSession(schoolId, targetSessionId);
        sessionAccess.requireWritable(target);

        List<ClassTeacherResponsibility> sourceRows =
                responsibilityRepository.findByAcademicSessionIdAndSchoolId(sourceSessionId, schoolId);

        int copied = 0, alreadyCopied = 0, skippedTeacher = 0, skippedClass = 0, skippedSection = 0, conflicts = 0, failures = 0;
        List<CopyRowResult> details = new ArrayList<>(sourceRows.size());

        for (ClassTeacherResponsibility row : sourceRows) {
            Evaluation evaluation;
            try {
                evaluation = worker.attempt(schoolId, row, targetSessionId);
            } catch (RuntimeException ex) {
                failures++;
                details.add(new CopyRowResult(row.getId(), null, Outcome.FAILURE.name(), "Copy failed; inspect server logs"));
                log.error("Class-teacher responsibility copy failed for source id={}, source={}, target={}",
                        row.getId(), sourceSessionId, targetSessionId, ex);
                continue;
            }
            switch (evaluation.outcome()) {
                case COPIED -> copied++;
                case ALREADY_COPIED -> alreadyCopied++;
                case SKIPPED_INELIGIBLE_TEACHER -> skippedTeacher++;
                case SKIPPED_INVALID_CLASS -> skippedClass++;
                case SKIPPED_INVALID_SECTION -> skippedSection++;
                case CONFLICT -> conflicts++;
                case FAILURE -> failures++;
            }
            Long targetId = evaluation.entry() != null ? evaluation.entry().getId() : null;
            details.add(new CopyRowResult(row.getId(), targetId, evaluation.outcome().name(), evaluation.reason()));
        }

        CopyResult result = new CopyResult(sourceSessionId, targetSessionId, sourceRows.size(), copied, alreadyCopied,
                skippedTeacher, skippedClass, skippedSection, conflicts, failures, List.copyOf(details));

        log.warn("Class-teacher responsibility copy completed: source={}, target={}, scanned={}, copied={}, "
                        + "alreadyCopied={}, skippedIneligibleTeacher={}, skippedInvalidClass={}, skippedInvalidSection={}, "
                        + "conflicts={}, failures={}",
                sourceSessionId, targetSessionId, result.scanned(), copied, alreadyCopied,
                skippedTeacher, skippedClass, skippedSection, conflicts, failures);

        try {
            auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "COPY_CLASS_TEACHER_RESPONSIBILITY",
                    "ClassTeacherResponsibility", "SESSION:" + sourceSessionId + "->" + targetSessionId,
                    null, result.toString(), request.getRemoteAddr());
        } catch (RuntimeException ignored) {
            // Audit failure must never mask a completed copy result.
        }

        return result;
    }
}
