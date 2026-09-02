package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.NotificationDelivery;
import com.indraacademy.ias_management.notification.NotificationDeliveryStatus;
import com.indraacademy.ias_management.repository.NotificationDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryStateServiceTest {
    @Mock NotificationDeliveryRepository repository;

    @Test
    void onlyCurrentLeaseOwnerCanCompleteProcessingRow() {
        NotificationDelivery row = claimed("owner-a");
        when(repository.findById(1L)).thenReturn(Optional.of(row));
        NotificationDeliveryStateService service = new NotificationDeliveryStateService(repository);

        assertFalse(service.markSent(1L, "owner-b", "provider-id", LocalDateTime.now()));
        assertEquals(NotificationDeliveryStatus.PROCESSING, row.getStatus());
        assertTrue(service.markSent(1L, "owner-a", "provider-id", LocalDateTime.now()));
        assertEquals(NotificationDeliveryStatus.SENT, row.getStatus());
        assertNull(row.getLeaseOwner());
        assertEquals("provider-id", row.getProviderMessageId());
    }

    @Test
    void retryAndFinalTransitionsSanitizeOperationalErrors() {
        NotificationDelivery retry = claimed("owner");
        when(repository.findById(2L)).thenReturn(Optional.of(retry));
        NotificationDeliveryStateService service = new NotificationDeliveryStateService(repository);
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(1);
        assertTrue(service.markRetryable(2L, "owner", "x".repeat(2_100), retryAt));
        assertEquals(NotificationDeliveryStatus.FAILED_RETRYABLE, retry.getStatus());
        assertEquals(2_000, retry.getLastError().length());
        assertEquals(retryAt, retry.getNextAttemptAt());

        NotificationDelivery terminal = claimed("owner");
        when(repository.findById(3L)).thenReturn(Optional.of(terminal));
        assertTrue(service.markFinal(3L, "owner", "permanent"));
        assertEquals(NotificationDeliveryStatus.FAILED_FINAL, terminal.getStatus());
        assertNull(terminal.getNextAttemptAt());
    }

    private NotificationDelivery claimed(String owner) {
        NotificationDelivery row = new NotificationDelivery();
        row.setStatus(NotificationDeliveryStatus.PROCESSING);
        row.setLeaseOwner(owner);
        row.setLeaseUntil(LocalDateTime.now().plusMinutes(2));
        row.setProcessingStartedAt(LocalDateTime.now());
        return row;
    }
}
