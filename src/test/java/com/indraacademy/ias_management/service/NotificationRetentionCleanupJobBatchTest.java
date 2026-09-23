package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.repository.NotificationDeliveryRepository;
import com.indraacademy.ias_management.repository.NotificationRepository;
import com.indraacademy.ias_management.repository.UserNotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Batch loop behaviour: child-first ordering, one transaction per batch, failure isolation. */
@ExtendWith(MockitoExtension.class)
class NotificationRetentionCleanupJobBatchTest {
    @Mock NotificationRepository notifications;
    @Mock UserNotificationRepository inbox;
    @Mock NotificationDeliveryRepository deliveries;
    @Mock PlatformTransactionManager transactionManager;

    static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 8, 23, 2, 0);

    private NotificationRetentionCleanupJob job(int batchSize) {
        lenient().when(transactionManager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        return new NotificationRetentionCleanupJob(notifications, inbox, deliveries, transactionManager, batchSize);
    }

    @Test
    void deletesChildrenBeforeTheParentNotificationInEachBatch() {
        when(notifications.findIdsCreatedBefore(eq(CUTOFF), any())).thenReturn(List.of(1L, 2L));
        when(notifications.deleteByIdIn(List.of(1L, 2L))).thenReturn(2);

        assertThat(job(10).purgeCreatedBefore(CUTOFF)).isEqualTo(2);

        InOrder order = inOrder(deliveries, inbox, notifications);
        order.verify(deliveries).deleteByNotificationIdIn(List.of(1L, 2L));
        order.verify(inbox).deleteByNotificationIdIn(List.of(1L, 2L));
        order.verify(notifications).deleteByIdIn(List.of(1L, 2L));
    }

    @Test
    void aFailedBatchStopsTheRunWithoutThrowingAndKeepsEarlierBatchesCommitted() {
        when(notifications.findIdsCreatedBefore(eq(CUTOFF), any()))
                .thenReturn(List.of(1L, 2L))
                .thenReturn(List.of(3L, 4L));
        when(notifications.deleteByIdIn(List.of(1L, 2L))).thenReturn(2);
        when(notifications.deleteByIdIn(List.of(3L, 4L))).thenThrow(new DataIntegrityViolationException("fk"));

        int removed = job(2).purgeCreatedBefore(CUTOFF);

        assertThat(removed).isEqualTo(2);
        verify(transactionManager, times(1)).commit(any(TransactionStatus.class));
        verify(transactionManager, times(1)).rollback(any(TransactionStatus.class));
        verify(notifications, times(2)).findIdsCreatedBefore(eq(CUTOFF), any());
    }

    @Test
    void stopsAsSoonAsABatchComesBackShortOrEmpty() {
        when(notifications.findIdsCreatedBefore(eq(CUTOFF), any())).thenReturn(List.of(1L));
        when(notifications.deleteByIdIn(List.of(1L))).thenReturn(1);

        assertThat(job(500).purgeCreatedBefore(CUTOFF)).isEqualTo(1);
        verify(notifications, times(1)).findIdsCreatedBefore(eq(CUTOFF), any());
    }

    @Test
    void nothingExpiredMeansNoDeletes() {
        when(notifications.findIdsCreatedBefore(eq(CUTOFF), any())).thenReturn(List.of());

        assertThat(job(500).purgeCreatedBefore(CUTOFF)).isZero();
        verifyNoInteractions(deliveries, inbox);
        verify(notifications, never()).deleteByIdIn(any());
    }

    @Test
    void theScheduledRunUsesSixtyDayRetention() {
        assertThat(NotificationRetentionCleanupJob.RETENTION).isEqualTo(java.time.Period.ofDays(60));
    }
}
