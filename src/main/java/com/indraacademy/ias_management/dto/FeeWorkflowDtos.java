package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.FeeOperationalStatus;
import com.indraacademy.ias_management.entity.FeeConfigType;
import com.indraacademy.ias_management.entity.MidSessionFeePolicy;
import com.indraacademy.ias_management.entity.StudentFeeAssignmentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
            LocalDate billingEffectiveDate,
            BigDecimal prorationFactor,
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

    public record DiscountHistoryRow(Long id, String studentId, Long feeHeadId, String feeHeadName,
                                     FeeConfigType configType, BigDecimal value, LocalDate validFrom,
                                     LocalDate validUntil, String reason, String approvedBy,
                                     LocalDateTime createdAt, LocalDateTime revokedAt,
                                     String revokedBy, String revokeReason) {}

    public record TransportHistoryRow(Long id, String studentId, boolean enabled, Double distance,
                                      LocalDate effectiveFrom, LocalDate effectiveTo, String reason,
                                      String changedBy, LocalDateTime createdAt) {}

    public record FeeLifecycleHistory(String studentId, String academicSession,
                                      List<DiscountHistoryRow> discounts,
                                      List<TransportHistoryRow> transport) {}

    public record DiscountUpdateRequest(FeeConfigType configType, BigDecimal value,
                                        LocalDate validFrom, LocalDate validUntil, String reason) {}
    public record DiscountExpireRequest(LocalDate effectiveFrom, String reason) {}
    public record RevokeFutureRequest(String reason) {}
    public record TransportCorrectionRequest(boolean enabled, Double distance, String reason) {}

    public record GenerationBatchRow(Long id, String academicSession, LocalDate effectiveDate,
                                     List<Integer> selectedMonths, int requestedStudents,
                                     int successfulStudents, int failedStudents, int generatedMonths,
                                     int skippedMonths, String status, String initiatedBy,
                                     Long retryOfBatchId, LocalDateTime startedAt, LocalDateTime completedAt,
                                     List<String> failedStudentIds) {}

    public record ReconciliationRow(String studentId, String studentName, String className,
                                    StudentFeeAssignmentStatus status, List<Integer> assignedMonths,
                                    List<Integer> generatedMonths, List<Integer> missingMonths,
                                    String message) {}

    public record ReconciliationSummary(int totalStudents, int fullyGenerated, int partiallyGenerated,
                                        int notAssigned, int failed, int missingMonthCount,
                                        List<ReconciliationRow> students) {}
}
