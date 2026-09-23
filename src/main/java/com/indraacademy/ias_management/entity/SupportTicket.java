package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A technical support ticket raised by any authenticated user against the Edunexify platform
 * itself (not routed to the reporter's own school admin — see the Phase 1 report). ticketNumber
 * is assigned after the initial insert (it embeds the generated id, e.g. "EDX-1042") — see
 * SupportTicketService.createTicket for why this is a deliberate two-write sequence rather than
 * a single insert.
 */
@Entity
@Data
@Table(name = "support_ticket")
public class SupportTicket {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version @Column(nullable = false)
    private long revision;
    @Column(name = "ticket_number", unique = true)
    private String ticketNumber;
    @Column(name = "school_id", nullable = false)
    private Long schoolId;
    @Column(name = "reporter_user_id", nullable = false)
    private String reporterUserId;
    @Column(name = "reporter_name", nullable = false)
    private String reporterName;
    @Column(name = "reporter_role", nullable = false)
    private String reporterRole;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private SupportTicketCategory category;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;
    @Column(name = "screenshot_object_key")
    private String screenshotObjectKey;
    @Column
    private String route;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private SupportPlatform platform;
    @Column(name = "app_version")
    private String appVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private SupportTicketStatus status = SupportTicketStatus.OPEN;
    @Column(name = "internal_note", columnDefinition = "TEXT")
    private String internalNote;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
