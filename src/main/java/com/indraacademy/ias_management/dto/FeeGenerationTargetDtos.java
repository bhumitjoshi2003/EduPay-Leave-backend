package com.indraacademy.ias_management.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/** E5B: target-enrollment-driven fee generation for a whole AcademicSession. */
public final class FeeGenerationTargetDtos {
    private FeeGenerationTargetDtos() {}

    public enum GenerationOutcome {
        GENERATED, PARTIALLY_GENERATED, ALREADY_GENERATED, NO_RULE_CONFIGURED, ENROLLMENT_CHANGED, FAILED
    }

    public enum DriftStatus {
        CLEAN, PARTIAL_GENERATION, CLASS_MISMATCH, CANCELLED_TARGET_WITH_FEES, NO_TARGET_ENROLLMENT
    }

    /** Read-only E5C comparison. Generated class fields are null when the rows do not agree
     *  on one class; generatedClassIds/generatedClassNames retain the complete evidence. */
    public record TargetDriftRow(
            String studentId,
            String studentName,
            Long targetSessionId,
            String targetSessionLabel,
            Long authoritativeEnrollmentId,
            String authoritativeEnrollmentStatus,
            Long enrollmentClassId,
            String enrollmentClassName,
            Long generatedClassId,
            String generatedClassName,
            List<Long> generatedClassIds,
            List<String> generatedClassNames,
            List<Integer> generatedMonths,
            List<Integer> expectedMonths,
            List<Integer> missingMonths,
            DriftStatus driftStatus,
            List<String> warnings) {}

    public record MonthPreview(
            int month,
            boolean alreadyGenerated,
            BigDecimal baseAmountDue,
            BigDecimal discountAmount,
            BigDecimal busFeeDue,
            BigDecimal total,
            String message) {}

    public record StudentPreviewRow(
            String studentId,
            String studentName,
            Long targetEnrollmentId,
            String targetEnrollmentStatus,
            Long targetClassId,
            String targetClassName,
            Long targetSectionId,
            String targetSectionName,
            Boolean repeatingSameClass,
            BigDecimal totalDue,
            List<MonthPreview> months,
            List<Integer> alreadyGeneratedMonths,
            List<String> warnings,
            List<String> blockingErrors,
            boolean eligible) {}

    public record GenerationDecision(
            @NotBlank String studentId,
            @NotNull Long expectedTargetEnrollmentId,
            @NotNull Long expectedTargetClassId) {}

    public record GenerationRequest(
            @NotNull Long targetSessionId,
            @NotEmpty @Valid List<GenerationDecision> decisions) {}

    public record StudentGenerationResult(
            String studentId,
            GenerationOutcome outcome,
            int generatedMonths,
            int skippedMonths,
            String message) {}
}
