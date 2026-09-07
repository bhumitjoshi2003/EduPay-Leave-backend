package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.StudentEnrollmentBackfillReport;
import com.indraacademy.ias_management.dto.StudentEnrollmentBulkBackfillReport;
import com.indraacademy.ias_management.dto.StudentEnrollmentBulkBackfillSchoolFailure;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.SchoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Cross-tenant orchestrator for the one-time production enrollment bootstrap. Deliberately
 * does not touch {@link StudentEnrollmentBackfillService} or {@link StudentEnrollmentBackfillWorker} —
 * it only loops the already-approved, already-tested per-school backfill over every active school
 * (or an explicit subset), isolating each school's outcome so one bad tenant cannot block or
 * corrupt the others. No new persistence: this class holds no state of its own between calls, so
 * it is safe to invoke repeatedly (retry-safe) with no "has this already run" bookkeeping required —
 * idempotency comes entirely from the underlying per-school backfill.
 */
@Service
public class StudentEnrollmentBulkBackfillService {

    private static final Logger log = LoggerFactory.getLogger(StudentEnrollmentBulkBackfillService.class);

    private final SchoolRepository schoolRepository;
    private final StudentEnrollmentBackfillService backfillService;

    public StudentEnrollmentBulkBackfillService(
            SchoolRepository schoolRepository,
            StudentEnrollmentBackfillService backfillService) {
        this.schoolRepository = schoolRepository;
        this.backfillService = backfillService;
    }

    /**
     * @param asOfDate  date to backfill as-of; required.
     * @param dryRun    forwarded unchanged to {@link StudentEnrollmentBackfillService#backfillForSchool}.
     * @param schoolIds when non-null and non-empty, restricts processing to these schools (still
     *                  filtered to active schools); when null or empty, all active schools are processed.
     */
    public StudentEnrollmentBulkBackfillReport backfillActiveSchools(
            LocalDate asOfDate, boolean dryRun, List<Long> schoolIds) {
        if (asOfDate == null) {
            throw new IllegalArgumentException("asOfDate is required");
        }

        Set<Long> requested = (schoolIds == null || schoolIds.isEmpty()) ? null : Set.copyOf(schoolIds);
        List<School> schools = schoolRepository.findAll().stream()
                .filter(School::isActive)
                .filter(school -> requested == null || requested.contains(school.getId()))
                .toList();

        List<StudentEnrollmentBackfillReport> schoolReports = new ArrayList<>(schools.size());
        List<StudentEnrollmentBulkBackfillSchoolFailure> schoolFailures = new ArrayList<>();
        Totals totals = new Totals();

        for (School school : schools) {
            try {
                StudentEnrollmentBackfillReport report =
                        backfillService.backfillForSchool(school.getId(), asOfDate, dryRun);
                schoolReports.add(report);
                totals.add(report);
            } catch (RuntimeException ex) {
                schoolFailures.add(new StudentEnrollmentBulkBackfillSchoolFailure(
                        school.getId(), ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                log.error("Bulk enrollment backfill failed for schoolId={}; other schools continue processing.",
                        school.getId(), ex);
            }
        }

        log.info("Bulk enrollment backfill completed: dryRun={}, asOfDate={}, schoolsRequested={}, " +
                        "schoolsProcessed={}, schoolsFailed={}, scanned={}, eligible={}, created={}, " +
                        "alreadyPresent={}, conflicts={}, failures={}",
                dryRun, asOfDate, schools.size(), schoolReports.size(), schoolFailures.size(),
                totals.scanned, totals.eligible, totals.created, totals.alreadyPresent,
                totals.conflicts, totals.failures);

        return new StudentEnrollmentBulkBackfillReport(
                asOfDate, dryRun, schools.size(), schoolReports.size(), schoolFailures.size(),
                totals.scanned, totals.eligible, totals.created, totals.alreadyPresent,
                totals.skippedNoSession, totals.skippedInvalidClass, totals.skippedInvalidSection,
                totals.skippedStatus, totals.skippedInvalidDate, totals.conflicts, totals.failures,
                List.copyOf(schoolReports), List.copyOf(schoolFailures));
    }

    private static final class Totals {
        private int scanned;
        private int eligible;
        private int created;
        private int alreadyPresent;
        private int skippedNoSession;
        private int skippedInvalidClass;
        private int skippedInvalidSection;
        private int skippedStatus;
        private int skippedInvalidDate;
        private int conflicts;
        private int failures;

        void add(StudentEnrollmentBackfillReport r) {
            scanned += r.scanned();
            eligible += r.eligible();
            created += r.created();
            alreadyPresent += r.alreadyPresent();
            skippedNoSession += r.skippedNoSession();
            skippedInvalidClass += r.skippedInvalidClass();
            skippedInvalidSection += r.skippedInvalidSection();
            skippedStatus += r.skippedStatus();
            skippedInvalidDate += r.skippedInvalidDate();
            conflicts += r.conflicts();
            failures += r.failures();
        }
    }
}
