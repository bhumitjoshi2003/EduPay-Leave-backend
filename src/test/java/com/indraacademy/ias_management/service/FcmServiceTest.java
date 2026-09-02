package com.indraacademy.ias_management.service;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.SendResponse;
import com.indraacademy.ias_management.entity.DeviceToken;
import com.indraacademy.ias_management.notification.ExternalDeliveryOutcome;
import com.indraacademy.ias_management.repository.DeviceTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FcmServiceTest {
    @Mock DeviceTokenRepository tokenRepository;
    @Mock FirebaseMessagingGateway gateway;

    @Test
    void sendsAllDevicesAndInvalidTokenDoesNotBlockValidDevice() throws Exception {
        when(gateway.isAvailable()).thenReturn(true);
        when(tokenRepository.findByUserIdAndSchoolId("student-1", 2L))
                .thenReturn(List.of(token("good"), token("stale")));
        SendResponse good = response(true, "message-1", null);
        SendResponse stale = response(false, null, failure(MessagingErrorCode.UNREGISTERED));
        BatchResponse batch = mock(BatchResponse.class);
        when(batch.getResponses()).thenReturn(List.of(good, stale));
        when(gateway.send(eq(List.of("good", "stale")), eq("Title"), eq("Body"), anyMap())).thenReturn(batch);

        var result = new FcmService(tokenRepository, gateway)
                .deliverToUser("student-1", 2L, "Title", "Body");

        assertEquals(ExternalDeliveryOutcome.SENT, result.outcome());
        assertEquals("message-1", result.providerMessageId());
        verify(tokenRepository).deleteByTokenAndUserIdAndSchoolId("stale", "student-1", 2L);
        verify(tokenRepository, never()).deleteByTokenAndUserIdAndSchoolId("good", "student-1", 2L);
    }

    @Test
    void transientFailureIsRetryableAndNeverDeletesToken() throws Exception {
        when(gateway.isAvailable()).thenReturn(true);
        when(tokenRepository.findByUserIdAndSchoolId("student-1", 2L)).thenReturn(List.of(token("token")));
        FirebaseMessagingException unavailable = failure(MessagingErrorCode.UNAVAILABLE);
        SendResponse failed = response(false, null, unavailable);
        BatchResponse batch = mock(BatchResponse.class);
        when(batch.getResponses()).thenReturn(List.of(failed));
        when(gateway.send(anyList(), anyString(), anyString(), anyMap())).thenReturn(batch);

        var result = new FcmService(tokenRepository, gateway)
                .deliverToUser("student-1", 2L, "Title", "Body");

        assertEquals(ExternalDeliveryOutcome.RETRYABLE_FAILURE, result.outcome());
        verify(tokenRepository, never()).deleteByTokenAndUserIdAndSchoolId(anyString(), anyString(), anyLong());
    }

    @Test
    void ambiguousInvalidArgumentIsPermanentButDoesNotDeleteRegistration() throws Exception {
        when(gateway.isAvailable()).thenReturn(true);
        when(tokenRepository.findByUserIdAndSchoolId("student-1", 2L)).thenReturn(List.of(token("token")));
        FirebaseMessagingException invalid = failure(MessagingErrorCode.INVALID_ARGUMENT);
        SendResponse failed = response(false, null, invalid);
        BatchResponse batch = mock(BatchResponse.class);
        when(batch.getResponses()).thenReturn(List.of(failed));
        when(gateway.send(anyList(), anyString(), anyString(), anyMap())).thenReturn(batch);

        var result = new FcmService(tokenRepository, gateway)
                .deliverToUser("student-1", 2L, "Title", "Body");

        assertEquals(ExternalDeliveryOutcome.PERMANENT_FAILURE, result.outcome());
        verify(tokenRepository, never()).deleteByTokenAndUserIdAndSchoolId(anyString(), anyString(), anyLong());
    }

    @Test
    void unavailableFirebaseIsRetryableAndNoTokenIsADeliberateSkip() {
        when(gateway.isAvailable()).thenReturn(false);
        assertEquals(ExternalDeliveryOutcome.RETRYABLE_FAILURE,
                new FcmService(tokenRepository, gateway).deliverToUser("student-1", 2L, "T", "B").outcome());

        reset(gateway);
        when(gateway.isAvailable()).thenReturn(true);
        when(tokenRepository.findByUserIdAndSchoolId("student-1", 2L)).thenReturn(List.of());
        assertEquals(ExternalDeliveryOutcome.SKIPPED,
                new FcmService(tokenRepository, gateway).deliverToUser("student-1", 2L, "T", "B").outcome());
    }

    private DeviceToken token(String value) {
        DeviceToken token = new DeviceToken("student-1", value, LocalDateTime.now());
        token.setSchoolId(2L);
        return token;
    }

    private SendResponse response(boolean successful, String id, FirebaseMessagingException exception) {
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(successful);
        if (successful) when(response.getMessageId()).thenReturn(id);
        else when(response.getException()).thenReturn(exception);
        return response;
    }

    private FirebaseMessagingException failure(MessagingErrorCode code) {
        FirebaseMessagingException failure = mock(FirebaseMessagingException.class);
        when(failure.getMessagingErrorCode()).thenReturn(code);
        return failure;
    }
}
