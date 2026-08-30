package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public final class ParentDtos {
    private ParentDtos() {}

    public record CreateParentRequest(
            @NotBlank @Size(max = 50) String parentId,
            @NotBlank @Size(max = 200) String name,
            @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 20) String phoneNumber,
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
            boolean pickupAuthorized,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil) {}

    public record ParentSummary(String parentId, String name, String email, String phoneNumber,
                                boolean active, int linkedChildren) {}

    public record ChildAccess(Long relationshipId, String studentId, String studentName,
                              String className, String sectionName, String relationshipType,
                              boolean primaryGuardian, boolean canViewAttendance,
                              boolean canViewFees, boolean canPayFees, boolean canViewResults,
                              boolean canViewTimetable,
                              boolean canManageLeave, boolean pickupAuthorized,
                              LocalDate effectiveFrom, LocalDate effectiveUntil) {}

    public record ParentProfile(ParentSummary parent, List<ChildAccess> children) {}
}
