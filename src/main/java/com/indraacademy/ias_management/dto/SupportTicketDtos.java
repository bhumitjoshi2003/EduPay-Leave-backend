package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.SupportPlatform;
import com.indraacademy.ias_management.entity.SupportTicket;
import com.indraacademy.ias_management.entity.SupportTicketCategory;
import com.indraacademy.ias_management.entity.SupportTicketStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public class SupportTicketDtos {

    public record CreateRequest(
            @NotNull SupportTicketCategory category,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 5000) String description,
            String screenshotObjectKey,
            @Size(max = 500) String route,
            @NotNull SupportPlatform platform,
            @Size(max = 50) String appVersion) {
    }

    /** SUPER_ADMIN only — the one and only thing a reporter can never change on their own ticket. */
    public record StatusUpdateRequest(
            @NotNull SupportTicketStatus status,
            @Size(max = 2000) String internalNote) {
    }

    public record TicketSummary(Long id, String ticketNumber, Long schoolId, String schoolName,
                                String reporterUserId, String reporterName, String reporterRole,
                                SupportTicketCategory category, String title, SupportTicketStatus status,
                                SupportPlatform platform, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public static TicketSummary from(SupportTicket t, String schoolName) {
            return new TicketSummary(t.getId(), t.getTicketNumber(), t.getSchoolId(), schoolName,
                    t.getReporterUserId(), t.getReporterName(), t.getReporterRole(),
                    t.getCategory(), t.getTitle(), t.getStatus(), t.getPlatform(),
                    t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    /** internalNote is populated only when the caller is SUPER_ADMIN — see
     *  SupportTicketService.toDetail's two call sites. A reporter viewing their own ticket
     *  always receives this field as null, never the real note. */
    public record TicketDetail(Long id, String ticketNumber, Long schoolId, String schoolName,
                               String reporterUserId, String reporterName, String reporterRole,
                               SupportTicketCategory category, String title, String description,
                               String screenshotUrl, String route, SupportPlatform platform,
                               String appVersion, SupportTicketStatus status, String internalNote,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
    }
}
