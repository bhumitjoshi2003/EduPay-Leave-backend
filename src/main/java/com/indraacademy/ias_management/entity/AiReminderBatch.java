package com.indraacademy.ias_management.entity;

import java.time.LocalDateTime;

/**
 * The lifecycle fields every AI-copilot reminder batch shares, regardless of what it reminds
 * about — fee overdue, school-wide low attendance, or a teacher's own class.
 *
 * <p>Exists so the batch <i>state machine</i> (start → PENDING_APPROVAL → REJECTED | SENT |
 * PARTIALLY_SENT | FAILED) lives in exactly one place instead of being re-implemented per
 * workflow. The three batch entities are deliberate structural mirrors of one another and each
 * already exposes these accessors via Lombok {@code @Data}; declaring the interface adds no
 * fields and changes no persistence mapping, it only lets AiReminderBatchService operate on all
 * three without a cast or a copy of the transition rules.
 *
 * <p>Everything genuinely workflow-specific — threshold, className, criterion, studentIds — stays
 * on the concrete entity and is deliberately absent here.
 */
public interface AiReminderBatch {

    String getWorkflowId();

    String getStatus();

    void setStatus(String status);

    void setCompletedAt(LocalDateTime completedAt);
}
