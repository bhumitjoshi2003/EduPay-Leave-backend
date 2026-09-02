package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.entity.NotificationDelivery;
import com.indraacademy.ias_management.entity.UserNotification;
import com.indraacademy.ias_management.notification.*;
import com.indraacademy.ias_management.repository.NotificationDeliveryRepository;
import com.indraacademy.ias_management.repository.NotificationRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.UserNotificationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Canonical transactional publication boundary. It never performs network I/O. */
@Service
public class NotificationPublisher {
    private final NotificationRepository notificationRepository;
    private final UserNotificationRepository inboxRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final SchoolRepository schoolRepository;
    private final NotificationPublicationTransaction publicationTransaction;
    private final ObjectMapper objectMapper;

    public NotificationPublisher(NotificationRepository notificationRepository,
                                 UserNotificationRepository inboxRepository,
                                 NotificationDeliveryRepository deliveryRepository,
                                 SchoolRepository schoolRepository,
                                 NotificationPublicationTransaction publicationTransaction,
                                 ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.inboxRepository = inboxRepository;
        this.deliveryRepository = deliveryRepository;
        this.schoolRepository = schoolRepository;
        this.publicationTransaction = publicationTransaction;
        this.objectMapper = objectMapper;
    }

    public NotificationPublication publish(NotificationPublishRequest request) {
        validate(request);
        String key = blankToNull(request.idempotencyKey());
        if (key != null) {
            Optional<Notification> existing = notificationRepository.findBySchoolIdAndIdempotencyKey(request.schoolId(), key);
            if (existing.isPresent()) return existingPublication(existing.get(), request.schoolId());
        }
        try {
            return publicationTransaction.publishNew(request);
        } catch (DataIntegrityViolationException conflict) {
            // A concurrent publisher may have won the tenant-scoped idempotency
            // race. Its transaction has completed before the unique violation is
            // reported, so returning that publication is safe and deterministic.
            if (key != null) {
                Optional<Notification> existing = notificationRepository.findBySchoolIdAndIdempotencyKey(request.schoolId(), key);
                if (existing.isPresent()) return existingPublication(existing.get(), request.schoolId());
            }
            throw conflict;
        }
    }

    private NotificationPublication existingPublication(Notification notification, Long schoolId) {
        List<UserNotification> rows = inboxRepository.findByNotificationIdAndSchoolId(notification.getId(), schoolId);
        List<NotificationDelivery> deliveries = deliveryRepository.findByNotificationIdAndSchoolId(notification.getId(), schoolId);
        return new NotificationPublication(notification, rows, deliveries, true);
    }

    private void validate(NotificationPublishRequest r) {
        if (r == null || r.schoolId() == null) throw new IllegalArgumentException("Target school is required.");
        if (!schoolRepository.existsById(r.schoolId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target school does not exist.");
        if (r.eventCode() == null || r.category() == null || r.audience() == null
                || r.title() == null || r.title().isBlank() || r.message() == null || r.message().isBlank()) {
            throw new IllegalArgumentException("Event code, category, title, message, and audience are required.");
        }
        if (r.title().length() > 255) throw new IllegalArgumentException("Notification title is too long.");
        if (r.idempotencyKey() != null && r.idempotencyKey().length() > 180) throw new IllegalArgumentException("Idempotency key is too long.");
        if (r.actionRoute() != null && !r.actionRoute().isBlank() && !r.actionRoute().startsWith("/")) {
            throw new IllegalArgumentException("Action route must be an application-relative route.");
        }
        if (r.actionMetadata() != null && !r.actionMetadata().isBlank()) {
            if (r.actionMetadata().length() > 10_000) throw new IllegalArgumentException("Action metadata is too large.");
            try { objectMapper.readTree(r.actionMetadata()); }
            catch (Exception e) { throw new IllegalArgumentException("Action metadata must be valid JSON."); }
        }
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
