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

    /**
     * Regression: notification_channels has never actually been seeded for any school
     * (NotificationChannelService.seedDefaultsForSchool has no caller — confirmed live
     * against the dev DB, every school's table is empty). Before this fix, EMAIL silently
     * defaulted to disabled here, so every Notice Board notice sent with deliveryMode
     * EMAIL/BOTH created zero EMAIL NotificationDelivery rows — nothing was ever attempted
     * against Brevo, despite the Notice Board UI presenting Email as a working option and
     * the notice creation call itself reporting success. Confirmed live end-to-end after
     * the fix: real NotificationDelivery rows are created and reach SENT via the real
     * Brevo SMTP relay.
     */
    @Test
    void defaultsBothPushAndEmailEnabledWhenSchoolHasNoConfiguredChannels() {
        when(repository.findBySchoolId(2L)).thenReturn(List.of());
        var resolver = new NotificationChannelPolicyResolver(repository);

        assertThat(resolver.resolve(2L, NotificationEventCode.NOTICE_PUBLISHED,
                NotificationCategory.NOTICE_ANNOUNCEMENT,
                Set.of(ExternalDeliveryChannel.PUSH, ExternalDeliveryChannel.EMAIL)))
                .containsExactlyInAnyOrder(ExternalDeliveryChannel.PUSH, ExternalDeliveryChannel.EMAIL);
    }

    @Test
    void emailOnlyRequestIsNotSilentlyDroppedForAnUnconfiguredSchool() {
        when(repository.findBySchoolId(2L)).thenReturn(List.of());
        var resolver = new NotificationChannelPolicyResolver(repository);

        assertThat(resolver.resolve(2L, NotificationEventCode.NOTICE_PUBLISHED,
                NotificationCategory.NOTICE_ANNOUNCEMENT, Set.of(ExternalDeliveryChannel.EMAIL)))
                .containsExactly(ExternalDeliveryChannel.EMAIL);
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
