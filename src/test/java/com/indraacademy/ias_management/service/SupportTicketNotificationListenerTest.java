package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupportTicketNotificationListenerTest {
    @Mock BusinessNotificationService notifications;
    SupportTicketNotificationListener listener;

    @BeforeEach
    void setup() {
        listener = new SupportTicketNotificationListener(notifications);
    }

    private SupportTicketNotificationEvent inProgressEvent() {
        return new SupportTicketNotificationEvent(1L, 500L, 0L, "EDX-500", "T1", "IN_PROGRESS", "SA1");
    }

    private SupportTicketNotificationEvent resolvedEvent() {
        return new SupportTicketNotificationEvent(1L, 500L, 1L, "EDX-500", "T1", "RESOLVED", "SA1");
    }

    @Test
    void inProgressEventNotifiesTheReporterViaExistingPushAndEmailChannels() {
        listener.afterCommit(inProgressEvent());

        verify(notifications).direct(eq(1L), eq("T1"), eq(NotificationEventCode.SUPPORT_TICKET_IN_PROGRESS),
                eq(NotificationCategory.SYSTEM_ADMIN), anyString(),
                contains("EDX-500"), eq("SupportTicket"), eq("500"),
                eq("/dashboard/support-tickets"), eq("SA1"), anyString(),
                eq(Set.of(ExternalDeliveryChannel.PUSH, ExternalDeliveryChannel.EMAIL)));
    }

    @Test
    void resolvedEventUsesTheResolvedEventCodeAndMessage() {
        listener.afterCommit(resolvedEvent());

        verify(notifications).direct(eq(1L), eq("T1"), eq(NotificationEventCode.SUPPORT_TICKET_RESOLVED),
                any(), anyString(), contains("resolved"), anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void idempotencyKeyIsUniquePerTicketRevisionKindAndRecipient() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        listener.afterCommit(inProgressEvent());
        verify(notifications).direct(anyLong(), anyString(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), keyCaptor.capture(), any());

        assertThat(keyCaptor.getValue()).isEqualTo("support-ticket:500:0:IN_PROGRESS:T1");
    }

    @Test
    void movingFromInProgressToResolvedProducesADistinctIdempotencyKey() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        listener.afterCommit(inProgressEvent());
        listener.afterCommit(resolvedEvent());
        verify(notifications, times(2)).direct(anyLong(), anyString(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), keyCaptor.capture(), any());

        assertThat(keyCaptor.getAllValues()).containsExactly(
                "support-ticket:500:0:IN_PROGRESS:T1",
                "support-ticket:500:1:RESOLVED:T1");
    }

    @Test
    void aNotificationDispatchFailureIsSwallowedNotPropagated() {
        when(notifications.direct(anyLong(), anyString(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("push provider unavailable"));

        assertThatCode(() -> listener.afterCommit(inProgressEvent())).doesNotThrowAnyException();
    }
}
