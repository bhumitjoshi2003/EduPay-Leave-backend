package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the Phase 3 continuation's startup/retry/hourly scenarios (J, K, and the "idle Redis
 * mode never touches PostgreSQL" performance criterion): every method here must be a strict
 * no-op — including never calling redisTemplate.opsForStream() at all — when Redis mode is off,
 * and must only invoke worker.runOnce() (the one PostgreSQL touch) when there's a genuine reason.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDeliveryRedisMaintenanceSchedulerTest {
    @Mock private NotificationDeliveryWorker worker;
    @Mock private NotificationDeliveryRetryCoordinator retryCoordinator;
    @Mock private StringRedisTemplate redisTemplate;

    private NotificationDeliveryRedisMaintenanceScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new NotificationDeliveryRedisMaintenanceScheduler(worker, retryCoordinator, redisTemplate);
        ReflectionTestUtils.setField(scheduler, "streamKey", "notification:delivery:stream");
        ReflectionTestUtils.setField(scheduler, "consumerGroup", "notification-delivery-workers");
        ReflectionTestUtils.setField(scheduler, "pendingClaimMinIdleMs", 60000L);
    }

    // ─── Redis mode disabled: total no-op, matching today's behavior exactly ───

    @Test
    void startupRecoveryDoesNothingWhenRedisModeIsDisabled() {
        ReflectionTestUtils.setField(scheduler, "redisEnabled", false);
        scheduler.recoverOnStartup();
        verify(worker, never()).runOnce();
    }

    @Test
    void retryTickDoesNothingWhenRedisModeIsDisabled() {
        ReflectionTestUtils.setField(scheduler, "redisEnabled", false);
        scheduler.checkDueRetriesAndPendingEntries();
        verify(retryCoordinator, never()).hasDueRetries();
        verify(redisTemplate, never()).opsForStream();
        verify(worker, never()).runOnce();
    }

    @Test
    void hourlyReconciliationDoesNothingWhenRedisModeIsDisabled() {
        ReflectionTestUtils.setField(scheduler, "redisEnabled", false);
        scheduler.hourlyReconciliation();
        verify(worker, never()).runOnce();
    }

    // ─── Redis mode enabled ───

    @Test
    void startupRecoveryRunsOneClaimPass() {
        ReflectionTestUtils.setField(scheduler, "redisEnabled", true);
        scheduler.recoverOnStartup();
        verify(worker).runOnce();
    }

    @Test
    void hourlyReconciliationRunsOneClaimPass() {
        ReflectionTestUtils.setField(scheduler, "redisEnabled", true);
        scheduler.hourlyReconciliation();
        verify(worker).runOnce();
    }

    @Test
    void retryTickSkipsPostgresWhenNothingIsDueAndNoPendingEntriesExist() {
        ReflectionTestUtils.setField(scheduler, "redisEnabled", true);
        when(retryCoordinator.hasDueRetries()).thenReturn(false);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.pending(anyString(), anyString())).thenReturn(null);

        scheduler.checkDueRetriesAndPendingEntries();

        verify(worker, never()).runOnce();
        verify(retryCoordinator, never()).clearDueEntries();
    }

    @Test
    void retryTickRunsAClaimPassAndClearsDueEntriesWhenSomethingIsDue() {
        ReflectionTestUtils.setField(scheduler, "redisEnabled", true);
        when(retryCoordinator.hasDueRetries()).thenReturn(true);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.pending(anyString(), anyString())).thenReturn(null);

        scheduler.checkDueRetriesAndPendingEntries();

        verify(worker).runOnce();
        verify(retryCoordinator).clearDueEntries();
    }

    @Test
    void pendingEntryCheckIsSkippedWhenSummaryReportsZeroPendingMessages() {
        ReflectionTestUtils.setField(scheduler, "redisEnabled", true);
        when(retryCoordinator.hasDueRetries()).thenReturn(false);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        PendingMessagesSummary summary = mock(PendingMessagesSummary.class);
        when(summary.getTotalPendingMessages()).thenReturn(0L);
        when(streamOps.pending(anyString(), anyString())).thenReturn(summary);

        scheduler.checkDueRetriesAndPendingEntries();

        verify(worker, never()).runOnce();
    }

    @Test
    void aRedisFailureDuringTheRetryTickNeverThrows() {
        ReflectionTestUtils.setField(scheduler, "redisEnabled", true);
        when(retryCoordinator.hasDueRetries()).thenReturn(false);
        when(redisTemplate.opsForStream()).thenThrow(new RuntimeException("redis unavailable"));

        scheduler.checkDueRetriesAndPendingEntries(); // must not throw
    }
}
