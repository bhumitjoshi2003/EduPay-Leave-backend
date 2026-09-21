package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One row per issued presigned-upload authorization — the trust anchor for
 * {@code POST /api/files/complete}, which must never take a raw client-supplied objectKey at
 * face value (see FileUploadRequestService). Created PENDING when the upload URL is issued,
 * flipped to COMPLETED once verified and attached to its entity, or left PENDING to expire and
 * be swept up as an orphan by UploadIntentCleanupScheduler if the client never completes it
 * (e.g. the browser closes mid-upload).
 *
 * <p>DB-backed rather than a stateless signed token — this is the simplest safe model for this
 * codebase's existing conventions (every other short-lived authorization/reservation in this
 * application, e.g. Refund's PENDING reservation, is a plain row) and it directly doubles as the
 * orphan-tracking mechanism Phase 1's cleanup strategy needs, with no separate bookkeeping.
 */
@Entity
@Table(name = "upload_intent")
@Data
public class UploadIntent {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    /** The authenticated user who requested this upload — not necessarily the entity owner
     * (e.g. an ADMIN uploads a teacher's photo), always the actor for audit purposes. */
    @Column(name = "requested_by_user_id", nullable = false)
    private String requestedByUserId;

    /** Name of an {@link com.indraacademy.ias_management.service.UploadPurpose} constant. */
    @Column(name = "purpose", nullable = false)
    private String purpose;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Column(name = "object_key", nullable = false, unique = true)
    private String objectKey;

    @Column(name = "expected_content_type", nullable = false)
    private String expectedContentType;

    @Column(name = "expected_size", nullable = false)
    private long expectedSize;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
