package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.NotificationChannel;
import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import com.indraacademy.ias_management.repository.NotificationChannelRepository;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Central channel policy. IN_APP is implicit and intentionally not an outbox channel. */
@Service
public class NotificationChannelPolicyResolver {
    private final NotificationChannelRepository repository;

    public NotificationChannelPolicyResolver(NotificationChannelRepository repository) {
        this.repository = repository;
    }

    public Set<ExternalDeliveryChannel> resolve(Long schoolId, NotificationEventCode eventCode,
                                                NotificationCategory category,
                                                Set<ExternalDeliveryChannel> requested) {
        if (requested == null || requested.isEmpty()) return Set.of();
        if (isMandatory(eventCode, category)) return EnumSet.copyOf(requested);

        Map<String, NotificationChannel> configured = repository.findBySchoolId(schoolId).stream()
                .collect(Collectors.toMap(c -> c.getChannelType().toUpperCase(), Function.identity(), (a, b) -> a));
        EnumSet<ExternalDeliveryChannel> result = EnumSet.noneOf(ExternalDeliveryChannel.class);
        for (ExternalDeliveryChannel channel : requested) {
            NotificationChannel setting = configured.get(channel.name());
            // Existing schools historically have PUSH enabled even when no row was seeded.
            boolean enabled = setting != null ? setting.isEnabled() : channel == ExternalDeliveryChannel.PUSH;
            if (enabled) result.add(channel);
        }
        return result;
    }

    private boolean isMandatory(NotificationEventCode eventCode, NotificationCategory category) {
        return eventCode == NotificationEventCode.ACCOUNT_SECURITY
                || eventCode == NotificationEventCode.PAYMENT_SUCCESS
                || category == NotificationCategory.ACCOUNT_SECURITY;
    }
}
