package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import com.indraacademy.ias_management.service.StudentYearEndDecision;

import java.util.List;

/** Read-only, backend-authoritative preview for one explicit source/target session pair. */
public record PromotionPreviewDTO(
        Long sourceSessionId,
        Long targetSessionId,
        boolean valid,
        List<Issue> errors,
        List<Candidate> candidates,
        List<UncoveredStudent> uncoveredStudents) {
    public record Issue(String code, String message) {}
    public record Candidate(
            String studentId, String studentName,
            Long sourceEnrollmentId, Long sourceSessionId,
            Long sourceClassId, String sourceClassName,
            Long sourceSectionId, String sourceSectionName,
            List<StudentYearEndDecision.Action> availableDecisions,
            StudentYearEndDecision.Action recommendedDecision,
            Long promoteTargetClassId, String promoteTargetClassName,
            Long detainTargetClassId, String detainTargetClassName,
            boolean promoteTargetSectionRequired,
            Long proposedPromoteTargetSectionId,
            Long proposedDetainTargetSectionId,
            StudentEnrollmentStatus proposedTargetStatus,
            List<Issue> errors, List<Issue> warnings,
            String appliedDecisionState) {}
    public record UncoveredStudent(String studentId, String studentName, String code, String message) {}
}
