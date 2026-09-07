package com.indraacademy.ias_management.dto;

import java.util.List;
import java.util.Map;

/** Per-student outcomes plus machine-readable batch summary. */
public record PromotionResultDTO(
        int submitted,
        Map<String, Long> summary,
        List<StudentOutcome> outcomes) {
    public record StudentOutcome(
            String studentId, String code, String message,
            Long sourceEnrollmentId, Long targetEnrollmentId,
            String targetEnrollmentStatus,
            boolean lifecycleFinalizationPending) {}
}
