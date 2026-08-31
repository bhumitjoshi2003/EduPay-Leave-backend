package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public final class ParentDtos {
    private ParentDtos() {}

    /**
     * parentId is deliberately absent — Edunexify generates it (par_YYnnnnnn). email is
     * required (unlike before) because it is now the only way to deliver the account-setup
     * link (Option A onboarding: no temporary password is ever admin-supplied or exposed —
     * see ParentPortalService.createParent).
     */
    public record CreateParentRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 20) String phoneNumber) {}

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 8, max = 100) String temporaryPassword) {}

    public record LinkStudentRequest(
            @NotBlank String studentId,
            @NotBlank String relationshipType,
            boolean primaryGuardian,
            Boolean canViewAttendance,
            Boolean canViewFees,
            Boolean canPayFees,
            Boolean canViewResults,
            Boolean canViewTimetable,
            Boolean canManageLeave,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil) {}

    public record ParentSummary(String parentId, String name, String email, String phoneNumber,
                                boolean active, int linkedChildren) {}

    /** School-wide directory aggregates for the summary cards — cheap COUNT queries, not a full parent scan. */
    public record ParentDirectoryStats(long totalParents, long activeParents,
                                       long linkedStudents, long unlinkedParents) {}

    public record ChildAccess(Long relationshipId, String studentId, String studentName,
                              String className, String sectionName, String relationshipType,
                              boolean primaryGuardian, boolean canViewAttendance,
                              boolean canViewFees, boolean canPayFees, boolean canViewResults,
                              boolean canViewTimetable,
                              boolean canManageLeave,
                              LocalDate effectiveFrom, LocalDate effectiveUntil) {}

    public record ParentProfile(ParentSummary parent, List<ChildAccess> children) {}
}
