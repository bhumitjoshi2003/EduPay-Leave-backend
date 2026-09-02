package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class NotificationRetryPolicyTest {
    private final NotificationRetryPolicy policy = new NotificationRetryPolicy(5);

    @Test
    void appliesBoundedExponentialScheduleAndStopsAtMaximum() {
        assertEquals(Duration.ofMinutes(1), policy.delayAfterFailure(1));
        assertEquals(Duration.ofMinutes(5), policy.delayAfterFailure(2));
        assertEquals(Duration.ofMinutes(15), policy.delayAfterFailure(3));
        assertEquals(Duration.ofHours(1), policy.delayAfterFailure(4));
        assertTrue(policy.mayRetry(4));
        assertFalse(policy.mayRetry(5));
        assertEquals(5, policy.maxAttempts());
    }
}
