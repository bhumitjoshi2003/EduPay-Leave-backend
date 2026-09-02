package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.notification.*;
import org.springframework.stereotype.Service;

import java.util.Set;

/** Canonical, server-owned adapter from business events to notification publications. */
@Service
public class BusinessNotificationService {
    private final NotificationPublisher publisher;

    public BusinessNotificationService(NotificationPublisher publisher) {
        this.publisher = publisher;
    }

    public NotificationPublication publish(Long schoolId, NotificationEventCode eventCode,
                                           NotificationCategory category, String title, String message,
                                           NotificationAudience audience, String sourceType, String sourceId,
                                           String actionRoute, String actorUserId, String idempotencyKey,
                                           Set<ExternalDeliveryChannel> channels) {
        return publisher.publish(new NotificationPublishRequest(
                schoolId, eventCode, category, NotificationPriority.NORMAL, title, message, audience,
                sourceType, sourceId, actionRoute, null, actorUserId, null, idempotencyKey, channels));
    }

    public NotificationPublication studentAndParents(Long schoolId, String studentId,
                                                      NotificationAudienceType audienceType,
                                                      NotificationEventCode eventCode,
                                                      NotificationCategory category, String title, String message,
                                                      String sourceType, String sourceId, String actionRoute,
                                                      String actorUserId, String idempotencyKey,
                                                      Set<ExternalDeliveryChannel> channels) {
        String safeStudentId = studentId.replace("\\", "\\\\").replace("\"", "\\\"");
        return publisher.publish(new NotificationPublishRequest(
                schoolId, eventCode, category, NotificationPriority.NORMAL, title, message,
                NotificationAudience.studentWithParents(studentId, audienceType), sourceType, sourceId,
                actionRoute, "{\"studentId\":\"" + safeStudentId + "\"}", actorUserId, null,
                idempotencyKey, channels));
    }

    public NotificationPublication direct(Long schoolId, String userId,
                                          NotificationEventCode eventCode,
                                          NotificationCategory category, String title, String message,
                                          String sourceType, String sourceId, String actionRoute,
                                          String actorUserId, String idempotencyKey,
                                          Set<ExternalDeliveryChannel> channels) {
        return publish(schoolId, eventCode, category, title, message, NotificationAudience.directUser(userId),
                sourceType, sourceId, actionRoute, actorUserId, idempotencyKey, channels);
    }
}
