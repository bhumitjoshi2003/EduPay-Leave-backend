package com.indraacademy.ias_management.notification;

import java.time.LocalDateTime;
import java.util.Set;

public record NotificationPublishRequest(
        Long schoolId,
        NotificationEventCode eventCode,
        NotificationCategory category,
        NotificationPriority priority,
        String title,
        String message,
        NotificationAudience audience,
        String sourceEntityType,
        String sourceEntityId,
        String actionRoute,
        String actionMetadata,
        String actorUserId,
        LocalDateTime expiresAt,
        String idempotencyKey,
        Set<ExternalDeliveryChannel> requestedChannels) {

    public NotificationPublishRequest {
        requestedChannels = requestedChannels == null ? Set.of() : Set.copyOf(requestedChannels);
    }
}
