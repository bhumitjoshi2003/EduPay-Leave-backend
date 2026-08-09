package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One completed AI Copilot chat turn, for debugging bad answers and catching
 * regressions — never read back through any user-facing endpoint (internal
 * ops/debugging only, queried directly against Postgres). userMessage/finalReply
 * can contain student-specific detail, so this table carries a short default
 * retention (see AiTraceCleanupScheduler) rather than being kept indefinitely.
 *
 * toolsCalledJson/ragResultsJson are compact JSON arrays (see AiTraceService) —
 * tool call summaries are metadata only (name/latency/success), never the raw
 * tool result payload, since several tools return *other* students' data.
 */
@Entity
@Data
@Table(name = "ai_trace_event",
    indexes = {
        @Index(name = "idx_ai_trace_trace_id", columnList = "trace_id"),
        @Index(name = "idx_ai_trace_created_at", columnList = "created_at"),
        @Index(name = "idx_ai_trace_school_id", columnList = "school_id")
    })
public class AiTraceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "conversation_id")
    private String conversationId;

    /** Nullable — only set for trace events shipped from the fee-reminder workflow's
     * start/resume requests, correlating them across those separate HTTP calls. */
    @Column(name = "workflow_id")
    private String workflowId;

    @Column(name = "user_message", columnDefinition = "TEXT")
    private String userMessage;

    @Column(name = "final_reply", columnDefinition = "TEXT")
    private String finalReply;

    /** JSON array of {name, latencyMs, success, error, resultSummary} — never raw tool payloads. */
    @Column(name = "tools_called_json", columnDefinition = "TEXT")
    private String toolsCalledJson;

    /** JSON array of {documentId, documentTitle, pageNumber, similarity, chunkPreview}. */
    @Column(name = "rag_results_json", columnDefinition = "TEXT")
    private String ragResultsJson;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "total_latency_ms")
    private Long totalLatencyMs;

    @Column(name = "errored", nullable = false)
    private boolean errored;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "interrupted", nullable = false)
    private boolean interrupted;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
