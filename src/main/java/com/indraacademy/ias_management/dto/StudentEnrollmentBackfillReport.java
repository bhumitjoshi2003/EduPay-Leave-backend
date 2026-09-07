package com.indraacademy.ias_management.dto;

import java.time.LocalDate;
import java.util.List;

public record StudentEnrollmentBackfillReport(
        Long schoolId,
        LocalDate asOfDate,
        boolean dryRun,
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
        List<StudentEnrollmentBackfillDetail> details) {
}
