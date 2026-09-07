package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;

/** Internal E1 domain contract. It is deliberately independent of the legacy promotion API DTOs. */
public final class StudentYearEndDecision {
    private StudentYearEndDecision() {}

    public enum Action { PROMOTE, DETAIN, PASS_OUT }

    public enum Outcome {
        PROMOTED, DETAINED, PASSED_OUT, ALREADY_APPLIED, CONFLICT, INVALID_SOURCE
    }

    public record AuditContext(String username, String role, String ipAddress) {
        public AuditContext {
            username = username == null || username.isBlank() ? "SYSTEM" : username;
            role = role == null || role.isBlank() ? "SYSTEM" : role;
            ipAddress = ipAddress == null || ipAddress.isBlank() ? "SYSTEM" : ipAddress;
        }
    }

    public record Request(
            Long schoolId,
            String studentId,
            Long sourceSessionId,
            Long targetSessionId,
            Long expectedSourceEnrollmentId,
            Long expectedSourceClassId,
            Action action,
            Long targetClassId,
            Long targetSectionId,
            AuditContext auditContext) {}

    public record Result(
            Outcome outcome,
            String message,
            Long sourceEnrollmentId,
            Long targetEnrollmentId,
            StudentEnrollmentStatus targetEnrollmentStatus,
            boolean lifecycleFinalizationPending) {}
}
