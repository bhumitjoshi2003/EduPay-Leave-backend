package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.notification.*;
import com.indraacademy.ias_management.observability.NotificationWorkerHeartbeat;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryWorkerTest {
    @Mock NotificationDeliveryClaimService claims;
    @Mock NotificationDeliveryStateService states;
    @Mock NotificationDeliveryFailureClassifier classifier;
    @Mock FcmService fcm;
    @Mock EmailService email;
    @Mock UserRepository users;
    @Mock SchoolRepository schools;
    @Mock NotificationWorkerHeartbeat heartbeat;
    @Mock NotificationDeliveryRetryCoordinator retryCoordinator;
    NotificationDeliveryWorker worker;

    @BeforeEach
    void setUp() {
        worker = new NotificationDeliveryWorker(claims, states, new NotificationRetryPolicy(5),
                classifier, fcm, email, users, schools, heartbeat, retryCoordinator);
    }

    // ─── Redis mode gating on the legacy scheduled poll (scenarios A/B) ───

    @Test
    void redisModeDisabled_legacyThirtySecondPollStillClaims() {
        ReflectionTestUtils.setField(worker, "redisEnabled", false);
        when(claims.claim(any(), anyInt(), any(), anyString())).thenReturn(List.of());

        worker.poll();

        verify(claims).claim(any(), anyInt(), any(), anyString());
    }

    @Test
    void redisModeEnabled_legacyPollReturnsWithoutTouchingPostgres() {
        ReflectionTestUtils.setField(worker, "redisEnabled", true);

        worker.poll();

        verifyNoInteractions(claims);
    }

    @Test
    void runOnceStillClaimsRegardlessOfRedisModeFlag() {
        // runOnce() is the method every wake-up source (Redis consumer, retry tick, startup,
        // hourly reconciliation) calls directly — it must never itself check the flag, only the
        // legacy scheduled poll() does that.
        ReflectionTestUtils.setField(worker, "redisEnabled", true);
        when(claims.claim(any(), anyInt(), any(), anyString())).thenReturn(List.of());

        worker.runOnce();

        verify(claims).claim(any(), anyInt(), any(), anyString());
    }

    @Test
    void successfulCycleSendsHeartbeat() {
        when(claims.claim(any(), anyInt(), any(), anyString())).thenReturn(List.of());

        worker.poll();

        verify(heartbeat).reportSuccess();
    }

    @Test
    void failedCycleDoesNotSendHeartbeat() {
        when(claims.claim(any(), anyInt(), any(), anyString())).thenThrow(new RuntimeException("claim failed"));

        worker.poll();

        verifyNoInteractions(heartbeat);
    }

    @Test
    void heartbeatFailureNeverBreaksTheWorker() {
        when(claims.claim(any(), anyInt(), any(), anyString())).thenReturn(List.of());
        doThrow(new RuntimeException("betterstack unreachable")).when(heartbeat).reportSuccess();

        assertThatCode(() -> worker.poll()).doesNotThrowAnyException();
    }

    @Test
    void pushSuccessRecordsSentUsingCurrentLease() {
        var delivery = delivery(ExternalDeliveryChannel.PUSH, 1);
        when(users.findByUserIdAndSchoolIdAndActiveTrue("student-1", 2L)).thenReturn(Optional.of(user(null)));
        when(fcm.deliverToUser(eq("student-1"), eq(2L), eq("Title"), eq("Message"), anyLong(),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(ExternalDeliveryResult.sent("fcm-id"));
        when(states.markSent(eq(10L), eq("lease"), eq("fcm-id"), any())).thenReturn(true);

        worker.processDelivery(delivery);

        verify(states).markSent(eq(10L), eq("lease"), eq("fcm-id"), any(LocalDateTime.class));
    }

    @Test
    void retryableFailureSchedulesBackoffAndMaximumAttemptBecomesFinal() {
        when(users.findByUserIdAndSchoolIdAndActiveTrue("student-1", 2L)).thenReturn(Optional.of(user(null)));
        when(fcm.deliverToUser(anyString(), anyLong(), anyString(), anyString(), anyLong(),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(ExternalDeliveryResult.retryable("unavailable"));

        worker.processDelivery(delivery(ExternalDeliveryChannel.PUSH, 1));
        verify(states).markRetryable(eq(10L), eq("lease"), eq("unavailable"), any(LocalDateTime.class));

        worker.processDelivery(delivery(ExternalDeliveryChannel.PUSH, 5));
        verify(states).markFinal(10L, "lease", "Retry limit reached: unavailable");
    }

    // ─── Retry coordinator hooks (scenarios G/I) ───

    @Test
    void retryableFailureSchedulesTheRedisRetrySignalWhenTheDbTransitionSucceeds() {
        when(users.findByUserIdAndSchoolIdAndActiveTrue("student-1", 2L)).thenReturn(Optional.of(user(null)));
        when(fcm.deliverToUser(anyString(), anyLong(), anyString(), anyString(), anyLong(),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(ExternalDeliveryResult.retryable("unavailable"));
        when(states.markRetryable(eq(10L), eq("lease"), eq("unavailable"), any(LocalDateTime.class))).thenReturn(true);

        worker.processDelivery(delivery(ExternalDeliveryChannel.PUSH, 1));

        verify(retryCoordinator).scheduleRetry(eq(10L), any(LocalDateTime.class));
        verify(retryCoordinator, never()).clearRetry(anyLong());
    }

    @Test
    void exhaustedRetriesClearAnyRedisRetrySignal_noEndlessRetryScheduling() {
        when(users.findByUserIdAndSchoolIdAndActiveTrue("student-1", 2L)).thenReturn(Optional.of(user(null)));
        when(fcm.deliverToUser(anyString(), anyLong(), anyString(), anyString(), anyLong(),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(ExternalDeliveryResult.retryable("unavailable"));
        when(states.markFinal(10L, "lease", "Retry limit reached: unavailable")).thenReturn(true);

        worker.processDelivery(delivery(ExternalDeliveryChannel.PUSH, 5)); // already at max attempts

        verify(retryCoordinator).clearRetry(10L);
        verify(retryCoordinator, never()).scheduleRetry(anyLong(), any());
    }

    @Test
    void sentDeliveryClearsAnyPriorRedisRetrySignal() {
        var delivery = delivery(ExternalDeliveryChannel.PUSH, 2);
        when(users.findByUserIdAndSchoolIdAndActiveTrue("student-1", 2L)).thenReturn(Optional.of(user(null)));
        when(fcm.deliverToUser(eq("student-1"), eq(2L), eq("Title"), eq("Message"), anyLong(),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(ExternalDeliveryResult.sent("fcm-id"));
        when(states.markSent(eq(10L), eq("lease"), eq("fcm-id"), any())).thenReturn(true);

        worker.processDelivery(delivery);

        verify(retryCoordinator).clearRetry(10L);
    }

    @Test
    void aStaleCompletionNeverTouchesTheRetryCoordinator() {
        // markRetryable returns false when the lease was already reclaimed by another worker —
        // there is nothing this instance's completion should do to Redis in that case.
        when(users.findByUserIdAndSchoolIdAndActiveTrue("student-1", 2L)).thenReturn(Optional.of(user(null)));
        when(fcm.deliverToUser(anyString(), anyLong(), anyString(), anyString(), anyLong(),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(ExternalDeliveryResult.retryable("unavailable"));
        when(states.markRetryable(eq(10L), eq("lease"), eq("unavailable"), any(LocalDateTime.class))).thenReturn(false);

        worker.processDelivery(delivery(ExternalDeliveryChannel.PUSH, 1));

        verifyNoInteractions(retryCoordinator);
    }

    @Test
    void inactiveRecipientIsSkippedWithoutTouchingHistoricalInbox() {
        when(users.findByUserIdAndSchoolIdAndActiveTrue("student-1", 2L)).thenReturn(Optional.empty());
        worker.processDelivery(delivery(ExternalDeliveryChannel.PUSH, 1));
        verify(states).markSkipped(10L, "lease", "Recipient is inactive or no longer belongs to school");
        verifyNoInteractions(fcm, email);
    }

    @Test
    void emailUsesCurrentAddressAndClassifiesProviderFailure() {
        User user = user("current@example.com");
        School school = new School();
        school.setName("Edunexify School");
        when(users.findByUserIdAndSchoolIdAndActiveTrue("student-1", 2L)).thenReturn(Optional.of(user));
        when(schools.findById(2L)).thenReturn(Optional.of(school));
        when(email.buildAnnouncementHtml("Title", "Message", "Edunexify School")).thenReturn("<html>message</html>");
        doThrow(new NotificationEmailDeliveryException("temporary", new java.net.SocketTimeoutException()))
                .when(email).sendNotificationEmailOrThrow(eq("current@example.com"), eq("Title"), anyString());
        when(classifier.classify(any())).thenReturn(ExternalDeliveryOutcome.RETRYABLE_FAILURE);

        worker.processDelivery(delivery(ExternalDeliveryChannel.EMAIL, 1));

        verify(states).markRetryable(eq(10L), eq("lease"), contains("NotificationEmailDeliveryException"), any());
    }

    @Test
    void missingCurrentEmailIsSkipped() {
        when(users.findByUserIdAndSchoolIdAndActiveTrue("student-1", 2L)).thenReturn(Optional.of(user(null)));
        worker.processDelivery(delivery(ExternalDeliveryChannel.EMAIL, 1));
        verify(states).markSkipped(10L, "lease", "Recipient has no email address");
        verifyNoInteractions(email);
    }

    private ClaimedNotificationDelivery delivery(ExternalDeliveryChannel channel, int attempt) {
        return new ClaimedNotificationDelivery(10L, 2L, 20L, "student-1", channel,
                "snapshot@example.com", "Title", "Message", attempt, "lease");
    }

    private User user(String address) {
        User user = new User();
        user.setUserId("student-1");
        user.setSchoolId(2L);
        user.setEmail(address);
        user.setActive(true);
        return user;
    }
}
