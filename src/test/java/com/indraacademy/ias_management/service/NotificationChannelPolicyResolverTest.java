package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.NotificationChannel;
import com.indraacademy.ias_management.notification.*;
import com.indraacademy.ias_management.repository.NotificationChannelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationChannelPolicyResolverTest {
    @Mock NotificationChannelRepository repository;

    @Test
    void preservesLegacyPushDefaultButDoesNotEnableEmailWithoutConfiguration() {
        when(repository.findBySchoolId(2L)).thenReturn(List.of());
        var resolver = new NotificationChannelPolicyResolver(repository);

        assertThat(resolver.resolve(2L, NotificationEventCode.NOTICE_PUBLISHED,
                NotificationCategory.NOTICE_ANNOUNCEMENT,
                Set.of(ExternalDeliveryChannel.PUSH, ExternalDeliveryChannel.EMAIL)))
                .containsExactly(ExternalDeliveryChannel.PUSH);
    }

    @Test
    void respectsConfiguredChannelSuppressionAndEnablement() {
        when(repository.findBySchoolId(2L)).thenReturn(List.of(channel("PUSH", false), channel("EMAIL", true)));
        var resolver = new NotificationChannelPolicyResolver(repository);

        assertThat(resolver.resolve(2L, NotificationEventCode.FEE_REMINDER,
                NotificationCategory.FEES_PAYMENTS,
                Set.of(ExternalDeliveryChannel.PUSH, ExternalDeliveryChannel.EMAIL)))
                .containsExactly(ExternalDeliveryChannel.EMAIL);
    }

    @Test
    void mandatorySecurityAndPaymentEventsCannotBeSuppressed() {
        var resolver = new NotificationChannelPolicyResolver(repository);
        Set<ExternalDeliveryChannel> requested = Set.of(ExternalDeliveryChannel.PUSH, ExternalDeliveryChannel.EMAIL);

        assertThat(resolver.resolve(2L, NotificationEventCode.ACCOUNT_SECURITY,
                NotificationCategory.ACCOUNT_SECURITY, requested)).containsExactlyInAnyOrderElementsOf(requested);
        assertThat(resolver.resolve(2L, NotificationEventCode.PAYMENT_SUCCESS,
                NotificationCategory.FEES_PAYMENTS, requested)).containsExactlyInAnyOrderElementsOf(requested);
    }

    private NotificationChannel channel(String type, boolean enabled) {
        NotificationChannel channel = new NotificationChannel();
        channel.setSchoolId(2L);
        channel.setChannelType(type);
        channel.setEnabled(enabled);
        return channel;
    }
}
