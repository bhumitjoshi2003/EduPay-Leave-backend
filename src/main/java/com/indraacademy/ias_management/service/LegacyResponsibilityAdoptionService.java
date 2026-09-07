package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.ResponsibilityAdoptionReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Phase F5A coordinator — see {@link LegacyTimetableAdoptionService} for why this has no
 *  transaction boundary of its own and simply delegates to {@link LegacyResponsibilityAdoptionWorker}. */
@Service
public class LegacyResponsibilityAdoptionService {

    private static final Logger log = LoggerFactory.getLogger(LegacyResponsibilityAdoptionService.class);

    private final LegacyResponsibilityAdoptionWorker worker;

    public LegacyResponsibilityAdoptionService(LegacyResponsibilityAdoptionWorker worker) {
        this.worker = worker;
    }

    public ResponsibilityAdoptionReport adopt(Long schoolId, Long academicSessionId, boolean dryRun) {
        if (schoolId == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        if (academicSessionId == null) {
            throw new IllegalArgumentException("academicSessionId is required — it is never inferred.");
        }

        LegacyResponsibilityAdoptionWorker.ClassificationResult result = dryRun
                ? worker.classify(schoolId, academicSessionId)
                : worker.classifyAndApply(schoolId, academicSessionId);

        LegacyResponsibilityAdoptionWorker.Counters c = result.counters();
        ResponsibilityAdoptionReport report = new ResponsibilityAdoptionReport(
                schoolId, academicSessionId, dryRun,
                c.scanned, c.safe, c.requiresAdminConfirmation, c.invalidOrConflicting, c.alreadyAdopted,
                c.adopted, result.details());

        log.info("Legacy class-teacher responsibility adoption completed: schoolId={}, academicSessionId={}, "
                        + "dryRun={}, scanned={}, safe={}, requiresAdminConfirmation={}, invalidOrConflicting={}, "
                        + "alreadyAdopted={}, adopted={}",
                schoolId, academicSessionId, dryRun, report.scanned(), report.safe(),
                report.requiresAdminConfirmation(), report.invalidOrConflicting(), report.alreadyAdopted(),
                report.adopted());
        return report;
    }
}
