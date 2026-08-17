package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable, Spring-owned record of one AI-copilot-proposed leave approve/reject batch.
 *
 * <p>Implements {@link AiReminderBatch} so it inherits the reminder workflows' lifecycle
 * wholesale — PENDING_APPROVAL → REJECTED | SENT | PARTIALLY_SENT | FAILED, the durable
 * rejection persistence, and the "a rejected batch can never be approved or dispatched" guards.
 * The status vocabulary is deliberately shared rather than renamed: SENT here means "the
 * decisions were applied", and reusing it keeps one state machine across every workflow instead
 * of a near-identical second one.
 *
 * <p>Unlike a reminder batch, this one records a <i>decision</i> (APPROVE or REJECT the leave)
 * separately from the batch's own approval status. Those are two different things and conflating
 * them would make the audit trail unreadable: a batch can be REJECTED (the admin declined to act)
 * while its decision was APPROVED (what would have happened to the leaves).
 */
@Entity
@Data
@Table(name = "ai_leave_decision_batch",
    indexes = {
        @Index(name = "idx_ai_leave_decision_batch_school", columnList = "school_id"),
        @Index(name = "idx_ai_leave_decision_batch_requester", columnList = "requester_user_id")
    })
public class AiLeaveDecisionBatch implements AiReminderBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, unique = true, length = 64)
    private String workflowId;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "requester_user_id", nullable = false, length = 64)
    private String requesterUserId;

    @Column(name = "requester_role", nullable = false, length = 32)
    private String requesterRole;

    /**
     * The class this batch is confined to, or null for a school-wide (admin) batch. Set from the
     * teacher's own classTeacher field at start and re-verified on every later call — a teacher
     * batch can never widen to another class, even though the underlying leave API itself is only
     * school-scoped.
     */
    @Column(name = "class_name", length = 64)
    private String className;

    /** What to do with the leaves: APPROVED or REJECTED (a LeaveStatus name). */
    @Column(name = "decision", nullable = false, length = 16)
    private String decision;

    /** JSON array of leave IDs, snapshotted when the batch was proposed. */
    @Column(name = "leave_ids", nullable = false, columnDefinition = "TEXT")
    private String leaveIds;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING_APPROVAL";

    @Column(name = "applied_count", nullable = false)
    private int appliedCount = 0;

    /** Leaves deliberately left alone — no longer PENDING, deleted, or outside the batch's class. */
    @Column(name = "skipped_count", nullable = false)
    private int skippedCount = 0;

    @Column(name = "failed_count", nullable = false)
    private int failedCount = 0;

    /** JSON array of {leaveId, outcome} — appliedCount/skippedCount/failedCount are authoritative. */
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
