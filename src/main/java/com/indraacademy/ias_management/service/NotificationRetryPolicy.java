package com.indraacademy.ias_management.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class NotificationRetryPolicy {
    private static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1));
    private final int maxAttempts;

    public NotificationRetryPolicy(@Value("${notification.delivery.max-attempts:5}") int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public boolean mayRetry(int completedAttemptCount) {
        return completedAttemptCount < maxAttempts;
    }

    public Duration delayAfterFailure(int completedAttemptCount) {
        int index = Math.max(0, Math.min(completedAttemptCount - 1, RETRY_DELAYS.size() - 1));
        return RETRY_DELAYS.get(index);
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
