package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.entity.NotificationDelivery;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.entity.UserNotification;
import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationDeliveryStatus;
import com.indraacademy.ias_management.notification.NotificationPriority;
import com.indraacademy.ias_management.notification.NotificationPublication;
import com.indraacademy.ias_management.notification.NotificationPublishRequest;
import com.indraacademy.ias_management.repository.NotificationDeliveryRepository;
import com.indraacademy.ias_management.repository.NotificationRepository;
import com.indraacademy.ias_management.repository.UserNotificationRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Persists one publication atomically. No network call is allowed here. */
@Service
public class NotificationPublicationTransaction {
    private final NotificationRepository notificationRepository;
    private final UserNotificationRepository inboxRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final UserRepository userRepository;
    private final NotificationRecipientResolver recipientResolver;
    private final NotificationChannelPolicyResolver channelPolicy;

    public NotificationPublicationTransaction(NotificationRepository notificationRepository,
                                              UserNotificationRepository inboxRepository,
                                              NotificationDeliveryRepository deliveryRepository,
                                              UserRepository userRepository,
                                              NotificationRecipientResolver recipientResolver,
                                              NotificationChannelPolicyResolver channelPolicy) {
        this.notificationRepository = notificationRepository;
        this.inboxRepository = inboxRepository;
        this.deliveryRepository = deliveryRepository;
        this.userRepository = userRepository;
        this.recipientResolver = recipientResolver;
        this.channelPolicy = channelPolicy;
    }

    @Transactional
    public NotificationPublication publishNew(NotificationPublishRequest request) {
        Set<String> recipientIds = recipientResolver.resolve(request.schoolId(), request.audience());
        Notification notification = notificationRepository.saveAndFlush(toEntity(request));
        List<UserNotification> inboxRows = inboxRepository.saveAllAndFlush(recipientIds.stream()
                .map(userId -> inboxRow(notification, request.schoolId(), userId)).toList());

        Set<ExternalDeliveryChannel> channels = channelPolicy.resolve(
                request.schoolId(), request.eventCode(), request.category(), request.requestedChannels());
        List<NotificationDelivery> deliveries = createDeliveries(notification, inboxRows, channels);
        if (!deliveries.isEmpty()) deliveries = deliveryRepository.saveAllAndFlush(deliveries);
        return new NotificationPublication(notification, List.copyOf(inboxRows), List.copyOf(deliveries), false);
    }

    private List<NotificationDelivery> createDeliveries(Notification notification,
                                                        List<UserNotification> rows,
                                                        Set<ExternalDeliveryChannel> channels) {
        if (rows.isEmpty() || channels.isEmpty()) return List.of();
        Map<String, User> users = userRepository.findBySchoolIdAndActiveTrueAndUserIdIn(
                        notification.getSchoolId(), rows.stream().map(UserNotification::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity()));
        List<NotificationDelivery> result = new ArrayList<>();
        for (UserNotification row : rows) {
            User user = users.get(row.getUserId());
            for (ExternalDeliveryChannel channel : channels) {
                NotificationDelivery delivery = new NotificationDelivery();
                delivery.setSchoolId(notification.getSchoolId());
                delivery.setNotification(notification);
                delivery.setUserNotification(row);
                delivery.setRecipientUserId(row.getUserId());
                delivery.setChannel(channel);
                if (channel == ExternalDeliveryChannel.EMAIL) {
                    String email = user == null ? null : user.getEmail();
                    delivery.setDestination(email);
                    if (email == null || email.isBlank()) {
                        delivery.setStatus(NotificationDeliveryStatus.SKIPPED);
                        delivery.setLastError("Recipient has no email address.");
                    }
                } else {
                    // PUSH delivery resolves the user's current device tokens at send time.
                    delivery.setDestination(row.getUserId());
                }
                result.add(delivery);
            }
        }
        return result;
    }

    private Notification toEntity(NotificationPublishRequest request) {
        Notification n = new Notification();
        n.setSchoolId(request.schoolId());
        n.setEventCode(request.eventCode());
        n.setCategory(request.category());
        n.setPriority(request.priority() == null ? NotificationPriority.NORMAL : request.priority());
        n.setTitle(request.title().trim());
        n.setMessage(request.message().trim());
        n.setType(request.eventCode().name());
        n.setAudience(switch (request.audience().type()) {
            case WHOLE_SCHOOL -> "ALL";
            case STUDENT_WITH_LEAVE_PARENTS -> "STUDENT_WITH_LEAVE_PARENTS:" + request.audience().value();
            case STUDENT_WITH_FEE_PARENTS -> "STUDENT_WITH_FEE_PARENTS:" + request.audience().value();
            case STUDENT_WITH_ATTENDANCE_PARENTS -> "STUDENT_WITH_ATTENDANCE_PARENTS:" + request.audience().value();
            case STUDENT_WITH_RESULT_PARENTS -> "STUDENT_WITH_RESULT_PARENTS:" + request.audience().value();
            case STUDENTS -> "STUDENTS";
            case TEACHERS -> "TEACHERS";
            case PARENTS -> "PARENTS";
            case CLASS -> "CLASS:" + request.audience().value();
            case CLASS_WITH_TEACHER -> "CLASS_WITH_TEACHER:" + request.audience().value();
            case ROLE -> "ROLE:" + request.audience().value();
            case DIRECT_USER -> request.audience().value();
        });
        n.setCreatedBy(request.actorUserId());
        n.setActorUserId(request.actorUserId());
        n.setSourceEntityType(blankToNull(request.sourceEntityType()));
        n.setSourceEntityId(blankToNull(request.sourceEntityId()));
        n.setActionRoute(blankToNull(request.actionRoute()));
        n.setActionMetadata(blankToNull(request.actionMetadata()));
        n.setExpiresAt(request.expiresAt());
        n.setIdempotencyKey(blankToNull(request.idempotencyKey()));
        n.setChannel(request.requestedChannels().contains(ExternalDeliveryChannel.EMAIL) ? "EMAIL" : "PUSH");
        n.setCreatedAt(LocalDateTime.now());
        return n;
    }

    private UserNotification inboxRow(Notification notification, Long schoolId, String userId) {
        UserNotification row = new UserNotification();
        row.setSchoolId(schoolId);
        row.setUserId(userId);
        row.setNotification(notification);
        row.setIsRead(false);
        row.setCreatedAt(notification.getCreatedAt());
        return row;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
