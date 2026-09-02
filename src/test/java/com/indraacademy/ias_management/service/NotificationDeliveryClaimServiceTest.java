package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.entity.NotificationDelivery;
import com.indraacademy.ias_management.entity.UserNotification;
import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationDeliveryStatus;
import com.indraacademy.ias_management.repository.NotificationDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryClaimServiceTest {
    @Mock NotificationDeliveryRepository repository;

    @Test
    void claimsBoundedRowsAndPersistsLeaseBeforeReturningSnapshots() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 8, 0);
        NotificationDelivery row = row(7L, NotificationDeliveryStatus.PENDING, 0);
        when(repository.lockEligibleIds(now, 25)).thenReturn(List.of(7L));
        when(repository.findAllById(List.of(7L))).thenReturn(List.of(row));

        var claimed = new NotificationDeliveryClaimService(repository)
                .claim(now, 25, Duration.ofMinutes(2), "worker-a");

        assertEquals(1, claimed.size());
        assertEquals(1, claimed.getFirst().attemptCount());
        assertEquals(NotificationDeliveryStatus.PROCESSING, row.getStatus());
        assertEquals(now.plusMinutes(2), row.getLeaseUntil());
        assertEquals("worker-a", row.getLeaseOwner());
        verify(repository).lockEligibleIds(now, 25);
        verify(repository).saveAll(List.of(row));
    }

    @Test
    void recoversExpiredProcessingLeaseAsANewAttempt() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 8, 0);
        NotificationDelivery row = row(8L, NotificationDeliveryStatus.PROCESSING, 2);
        row.setLeaseOwner("dead-worker");
        row.setLeaseUntil(now.minusSeconds(1));
        when(repository.lockEligibleIds(now, 10)).thenReturn(List.of(8L));
        when(repository.findAllById(List.of(8L))).thenReturn(List.of(row));

        var claimed = new NotificationDeliveryClaimService(repository)
                .claim(now, 10, Duration.ofMinutes(2), "replacement");

        assertEquals(3, claimed.getFirst().attemptCount());
        assertEquals("replacement", row.getLeaseOwner());
    }

    @Test
    void emptyClaimDoesNotLoadOrSaveRows() {
        LocalDateTime now = LocalDateTime.now();
        when(repository.lockEligibleIds(now, 5)).thenReturn(List.of());
        assertTrue(new NotificationDeliveryClaimService(repository)
                .claim(now, 5, Duration.ofMinutes(2), "worker").isEmpty());
        verify(repository, never()).findAllById(any());
        verify(repository, never()).saveAll(any());
    }

    private NotificationDelivery row(long id, NotificationDeliveryStatus status, int attempts) {
        Notification notification = new Notification();
        notification.setId(99L);
        notification.setTitle("Title");
        notification.setMessage("Message");
        UserNotification userNotification = new UserNotification();
        userNotification.setId(id + 1000);
        NotificationDelivery row = new NotificationDelivery();
        row.setId(id);
        row.setSchoolId(2L);
        row.setNotification(notification);
        row.setUserNotification(userNotification);
        row.setRecipientUserId("student-1");
        row.setChannel(ExternalDeliveryChannel.PUSH);
        row.setStatus(status);
        row.setAttemptCount(attempts);
        return row;
    }
}
