package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.observability.EntitlementRefreshHeartbeat;
import com.indraacademy.ias_management.repository.SchoolSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Covers only the Better Stack heartbeat behavior added to
 * EntitlementRefreshService.refreshAll() — not the entitlement rebuild logic
 * itself, which is covered elsewhere. Each case uses an empty/failing
 * subscriptionRepo.findAllActive() result so the per-school rebuild path
 * (which needs many other collaborators) is never entered.
 */
@ExtendWith(MockitoExtension.class)
class EntitlementRefreshServiceHeartbeatTest {

    @Mock SchoolSubscriptionRepository subscriptionRepo;
    @Mock EntitlementRefreshHeartbeat heartbeat;
    @InjectMocks EntitlementRefreshService service;

    @Test
    void successfulRefreshSendsHeartbeat() {
        when(subscriptionRepo.findAllActive()).thenReturn(List.of());

        service.refreshAll();

        verify(heartbeat).reportSuccess();
    }

    @Test
    void failedTopLevelRefreshDoesNotSendHeartbeat() {
        when(subscriptionRepo.findAllActive()).thenThrow(new RuntimeException("db unavailable"));

        assertThatThrownBy(service::refreshAll).isInstanceOf(RuntimeException.class);

        verifyNoInteractions(heartbeat);
    }

    @Test
    void heartbeatFailureNeverBreaksTheRefresh() {
        when(subscriptionRepo.findAllActive()).thenReturn(List.of());
        doThrow(new RuntimeException("betterstack unreachable")).when(heartbeat).reportSuccess();

        assertThatCode(service::refreshAll).doesNotThrowAnyException();
    }
}
