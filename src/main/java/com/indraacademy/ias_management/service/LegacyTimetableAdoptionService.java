package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.TimetableAdoptionReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phase F5A coordinator — thin wrapper around {@link LegacyTimetableAdoptionWorker} that has no
 * transaction boundary of its own (same split as {@code StudentEnrollmentBackfillService}/
 * {@code StudentEnrollmentBackfillWorker}): the worker's two entry points
 * ({@code classify}/{@code classifyAndApply}) must be invoked through Spring's proxy, which
 * requires them to live on a different bean than the caller.
 */
@Service
public class LegacyTimetableAdoptionService {

    private static final Logger log = LoggerFactory.getLogger(LegacyTimetableAdoptionService.class);

    private final LegacyTimetableAdoptionWorker worker;

    public LegacyTimetableAdoptionService(LegacyTimetableAdoptionWorker worker) {
        this.worker = worker;
    }

    public TimetableAdoptionReport adopt(Long schoolId, Long academicSessionId, boolean dryRun) {
        if (schoolId == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        if (academicSessionId == null) {
            throw new IllegalArgumentException("academicSessionId is required — it is never inferred.");
        }

        LegacyTimetableAdoptionWorker.ClassificationResult result = dryRun
                ? worker.classify(schoolId, academicSessionId)
                : worker.classifyAndApply(schoolId, academicSessionId);

        LegacyTimetableAdoptionWorker.Counters c = result.counters();
        TimetableAdoptionReport report = new TimetableAdoptionReport(
                schoolId, academicSessionId, dryRun,
                c.scanned, c.safe, c.requiresAdminConfirmation, c.invalidOrConflicting,
                c.alreadyAdopted, c.skipped, c.adopted,
                c.unresolvedClassMappings, c.unresolvedSectionMappings, c.invalidOrIneligibleTeachers,
                c.slotConflicts, c.teacherOverlaps, c.simultaneousGroupConcerns,
                result.details());

        log.info("Legacy timetable adoption completed: schoolId={}, academicSessionId={}, dryRun={}, "
                        + "scanned={}, safe={}, requiresAdminConfirmation={}, invalidOrConflicting={}, "
                        + "alreadyAdopted={}, skipped={}, adopted={}",
                schoolId, academicSessionId, dryRun, report.scanned(), report.safe(),
                report.requiresAdminConfirmation(), report.invalidOrConflicting(), report.alreadyAdopted(),
                report.skipped(), report.adopted());
        return report;
    }
}
