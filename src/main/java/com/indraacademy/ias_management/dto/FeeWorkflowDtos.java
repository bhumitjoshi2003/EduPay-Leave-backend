package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.FeeOperationalStatus;
import com.indraacademy.ias_management.entity.FeeConfigType;
import com.indraacademy.ias_management.entity.MidSessionFeePolicy;
import com.indraacademy.ias_management.entity.StudentFeeAssignmentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class FeeWorkflowDtos {
    private FeeWorkflowDtos() {}

    public record SettingsUpdate(
            FeeOperationalStatus operationalStatus,
            LocalDate activationDate,
            MidSessionFeePolicy midSessionPolicy,
            Boolean allowRetroactiveGeneration,
            Boolean automaticAnnualGeneration) {}

    public record AssignmentRequest(
            List<String> studentIds,
            String academicSession,
            LocalDate effectiveDate,
            List<Integer> months,
            String reason) {}

    public record AssignmentRow(
            String studentId,
            String studentName,
            String className,
            String sectionName,
            LocalDate joiningDate,
            StudentFeeAssignmentStatus status,
            LocalDate effectiveDate,
            List<Integer> selectedMonths,
            long generatedMonths,
            String message) {}

    public record AssignmentSummary(
            long totalStudents,
            long notAssigned,
            long ready,
            long generated,
            long partial,
            long excluded,
            long failed) {}

    public record MonthPreview(
            int month,
            boolean existing,
            boolean eligible,
            BigDecimal baseAmount,
            BigDecimal discountAmount,
            BigDecimal busFee,
            BigDecimal totalAmount,
            String message) {}

    public record StudentPreview(
            String studentId,
            String studentName,
            boolean eligible,
            BigDecimal totalAmount,
            List<MonthPreview> months,
            String message) {}

    public record GenerationResult(
            String studentId,
            int generated,
            int skipped,
            boolean successful,
            String message) {}

    public record TransportChangeRequest(
            List<String> studentIds,
            String academicSession,
            boolean enabled,
            Double distance,
            LocalDate effectiveFrom,
            String reason) {}

    public record BulkDiscountRequest(
            List<String> studentIds,
            Long academicSessionId,
            Long feeHeadId,
            FeeConfigType configType,
            BigDecimal value,
            LocalDate validFrom,
            LocalDate validUntil,
            String reason) {}

    public record StudentRecalculationResult(
            String studentId,
            boolean changeSaved,
            List<RecalculationEntryDto> months,
            String message) {}

    public record WorkflowChangeResult(
            int requestedStudents,
            int savedStudents,
            int recalculatedMonths,
            int skippedMonths,
            List<StudentRecalculationResult> students) {}
}
