package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable, Spring-owned record of one AI-copilot-triggered TEACHER-initiated attendance
 * reminder batch. Structural mirror of AiAttendanceReminderBatch (the admin, school-wide
 * variant), with two load-bearing differences: className is NOT NULL (a teacher batch always
 * belongs to exactly one class, resolved server-side — see
 * AiTeacherAttendanceWorkflowController.start), and teacherUserId (not adminUserId) records
 * exactly which teacher owns this batch, since — unlike an admin batch, which any admin in the
 * school may approve — only that same teacher may resume it.
 */
@Entity
@Data
@Table(name = "ai_teacher_attendance_reminder_batch",
    indexes = {
        @Index(name = "idx_ai_teacher_attendance_reminder_batch_school", columnList = "school_id"),
        @Index(name = "idx_ai_teacher_attendance_reminder_batch_teacher", columnList = "teacher_user_id")
    })
public class AiTeacherAttendanceReminderBatch implements AiReminderBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, unique = true, length = 64)
    private String workflowId;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "teacher_user_id", nullable = false, length = 64)
    private String teacherUserId;

    @Column(name = "session", nullable = false, length = 16)
    private String session;

    @Column(name = "class_name", nullable = false, length = 64)
    private String className;

    /** Only meaningful when criterion = BELOW_THRESHOLD; ignored for CONSECUTIVE_ABSENCE. */
    @Column(name = "threshold", nullable = false)
    private double threshold = 75.0;

    /**
     * Which attendance pattern selected this batch: BELOW_THRESHOLD (cumulative percentage) or
     * CONSECUTIVE_ABSENCE (absent the last N marked school days). Recorded so the audit trail
     * answers not just who was emailed but on what basis — the two can select disjoint sets of
     * students from the same class on the same day.
     */
    @Column(name = "criterion", nullable = false, length = 32)
    private String criterion = "BELOW_THRESHOLD";

    /** Streak length required, when criterion = CONSECUTIVE_ABSENCE. Null otherwise. */
    @Column(name = "min_consecutive_days")
    private Integer minConsecutiveDays;

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
