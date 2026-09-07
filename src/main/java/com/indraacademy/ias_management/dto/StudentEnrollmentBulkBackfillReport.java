package com.indraacademy.ias_management.dto;

import java.time.LocalDate;
import java.util.List;

public record StudentEnrollmentBulkBackfillReport(
        LocalDate asOfDate,
        boolean dryRun,
        int schoolsRequested,
        int schoolsProcessed,
        int schoolsFailed,
        int scanned,
        int eligible,
        int created,
        int alreadyPresent,
        int skippedNoSession,
        int skippedInvalidClass,
        int skippedInvalidSection,
        int skippedStatus,
        int skippedInvalidDate,
        int conflicts,
        int failures,
        List<StudentEnrollmentBackfillReport> schoolReports,
        List<StudentEnrollmentBulkBackfillSchoolFailure> schoolFailures) {
}
