package com.indraacademy.ias_management.notification;

public enum NotificationDeliveryStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    SKIPPED
}
