package com.indraacademy.ias_management.notification;

public record ClaimedNotificationDelivery(
        long id,
        Long schoolId,
        Long notificationId,
        String recipientUserId,
        ExternalDeliveryChannel channel,
        String destination,
        String title,
        String message,
        long inboxId,
        String eventCode,
        String actionRoute,
        String sourceEntityType,
        String sourceEntityId,
        String actionMetadata,
        int attemptCount,
        String leaseOwner) {
    public ClaimedNotificationDelivery(long id, Long schoolId, Long notificationId, String recipientUserId,
                                       ExternalDeliveryChannel channel, String destination, String title,
                                       String message, int attemptCount, String leaseOwner) {
        this(id, schoolId, notificationId, recipientUserId, channel, destination, title, message,
                0L, null, null, null, null, null, attemptCount, leaseOwner);
    }
}
