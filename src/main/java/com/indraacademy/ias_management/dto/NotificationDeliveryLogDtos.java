package com.indraacademy.ias_management.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SUPER_ADMIN notification delivery visibility. Status values are exactly what the pipeline
 * persists (PENDING, PROCESSING, SENT, FAILED_RETRYABLE, FAILED_FINAL, SKIPPED) plus the
 * synthetic IN_APP "STORED" (inbox row exists). SENT means the provider accepted the message,
 * never that it reached or was seen on a device/inbox. Never carries FCM tokens, credentials
 * or provider payloads; email destinations are masked.
 */
public final class NotificationDeliveryLogDtos {
    private NotificationDeliveryLogDtos() {}

    public record Row(long id, String channel, String status, long schoolId, String schoolName,
                      String recipientUserId, String recipientRole, long notificationId, String eventCode,
                      String title, int attemptCount, String lastError, String providerMessageId,
                      LocalDateTime createdAt, LocalDateTime sentAt, LocalDateTime nextAttemptAt,
                      Boolean read, LocalDateTime readAt) {}

    public record RowPage(List<Row> content, int page, int size, boolean hasNext) {}

    /** The same recipient's copy of the same notification on another channel. */
    public record RelatedChannel(long id, String channel, String status, int attemptCount,
                                 LocalDateTime sentAt, String lastError, Boolean read, LocalDateTime readAt) {}

    public record Detail(long id, String channel, String status, long schoolId, String schoolName,
                         String recipientUserId, String recipientRole, String maskedDestination,
                         long notificationId, String eventCode, String category, String title, String message,
                         String sourceEntityType, String sourceEntityId, int attemptCount, int maxAttempts,
                         String lastError, String providerMessageId, LocalDateTime createdAt,
                         LocalDateTime sentAt, LocalDateTime nextAttemptAt, LocalDateTime processingStartedAt,
                         Boolean read, LocalDateTime readAt, List<RelatedChannel> relatedChannels) {}

    /**
     * Per-channel outcome counts for one notification. accepted = SENT (provider accepted, not a
     * delivery receipt); failed = FAILED_FINAL; retrying = FAILED_RETRYABLE; skipped = SKIPPED;
     * queued = PENDING + PROCESSING. total is every delivery row on that channel.
     */
    public record ChannelCounts(long total, long accepted, long failed, long retrying, long skipped, long queued) {}

    /** stored = inbox rows (= recipients); opened = the recipient opened it in the app. */
    public record InAppCounts(long stored, long opened, long unopened) {}

    /**
     * One notification publication. push/email are null when that channel was never used for
     * this notification (no delivery rows). Delivery rows are only ever removed together with
     * their notification (NotificationRetentionCleanupJob), so a listed notification's counts are
     * complete.
     */
    public record SummaryRow(long notificationId, Long schoolId, String schoolName, String eventCode, String title,
                             String messagePreview, LocalDateTime createdAt, long totalRecipients, InAppCounts inApp,
                             ChannelCounts push, ChannelCounts email) {}

    public record SummaryPage(List<SummaryRow> content, int page, int size, boolean hasNext) {}
}
