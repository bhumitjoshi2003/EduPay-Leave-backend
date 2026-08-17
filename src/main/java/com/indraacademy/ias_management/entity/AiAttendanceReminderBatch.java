package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable, Spring-owned record of one AI-copilot-triggered attendance reminder batch.
 * Direct structural mirror of AiFeeReminderBatch — inserted at workflow START
 * (PENDING_APPROVAL) so an abandoned batch is still auditable; updated once at dispatch
 * time. The unique workflowId is what makes the dispatch step idempotent — see
 * AiAttendanceWorkflowController.dispatch().
 *
 * studentIds/outcomes are compact JSON, same plain-TEXT-column, manual-ObjectMapper-
 * serialization pattern as AiFeeReminderBatch — not a join table: this list is written
 * once, read once, and capped to a small admin-driven batch.
 */
@Entity
@Data
@Table(name = "ai_attendance_reminder_batch",
    indexes = {
        @Index(name = "idx_ai_attendance_reminder_batch_school", columnList = "school_id")
    })
public class AiAttendanceReminderBatch implements AiReminderBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, unique = true, length = 64)
    private String workflowId;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "admin_user_id", nullable = false, length = 64)
    private String adminUserId;

    @Column(name = "session", nullable = false, length = 16)
    private String session;

    @Column(name = "class_name", length = 64)
    private String className;

    @Column(name = "threshold", nullable = false)
    private double threshold = 75.0;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING_APPROVAL";

    /** JSON array of studentId strings, snapshotted when the batch was proposed. */
    @Column(name = "student_ids", nullable = false, columnDefinition = "TEXT")
    private String studentIds;

    @Column(name = "sent_count", nullable = false)
    private int sentCount = 0;

    @Column(name = "failed_count", nullable = false)
    private int failedCount = 0;

    /** JSON array of {studentId, status}, capped — sentCount/failedCount are authoritative. */
    @Column(name = "outcomes", columnDefinition = "TEXT")
    private String outcomes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
