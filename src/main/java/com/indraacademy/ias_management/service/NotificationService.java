package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.UserNotificationDTO;
import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.entity.UserNotification;
import com.indraacademy.ias_management.repository.NotificationRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.UserNotificationRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.indraacademy.ias_management.notification.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.Period;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Owns the durable in-app notification inbox. Recipient membership is resolved
 * once, when a notification is published; inbox reads never mutate data.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    @Autowired private NotificationRepository notificationRepository;
    @Autowired private UserNotificationRepository userNotificationRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private NotificationPublisher notificationPublisher;

    public Long resolveTargetSchoolId(Long requestedSchoolId) {
        if ("SUPER_ADMIN".equalsIgnoreCase(securityUtil.getRole())) {
            if (requestedSchoolId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "An explicit schoolId is required for SUPER_ADMIN notification operations.");
            }
            if (!schoolRepository.existsById(requestedSchoolId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target school does not exist.");
            }
            return requestedSchoolId;
        }

        Long authenticatedSchoolId = requireAuthenticatedSchool();
        if (requestedSchoolId != null && !Objects.equals(requestedSchoolId, authenticatedSchoolId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A school administrator cannot operate on another school's notifications.");
        }
        return authenticatedSchoolId;
    }

    public Notification createBroadNotification(Notification notification, HttpServletRequest request) {
        return createBroadNotification(notification, null, request);
    }

    public Notification createBroadNotification(Notification notification,
                                                Long requestedSchoolId,
                                                HttpServletRequest request) {
        if (notification == null || isBlank(notification.getTitle()) || isBlank(notification.getMessage())
                || isBlank(notification.getAudience())) {
            throw new IllegalArgumentException("Notification title, message, and audience are required.");
        }

        Long schoolId = resolveTargetSchoolId(requestedSchoolId);
        try {
            NotificationPublishRequest publishRequest = new NotificationPublishRequest(
                    schoolId,
                    eventCodeFor(notification),
                    categoryFor(notification),
                    notification.getPriority() == null ? NotificationPriority.NORMAL : notification.getPriority(),
                    notification.getTitle(), notification.getMessage(), audienceFor(notification.getAudience()),
                    notification.getSourceEntityType(), notification.getSourceEntityId(),
                    notification.getActionRoute(), notification.getActionMetadata(), securityUtil.getUsername(),
                    notification.getExpiresAt(), notification.getIdempotencyKey(), channelsFor(notification));
            NotificationPublication publication = notificationPublisher.publish(publishRequest);
            Notification saved = publication.notification();

            if (!publication.duplicate()) {
                auditService.log(
                        securityUtil.getUsername(), securityUtil.getRole(), "CREATE_NOTIFICATION",
                        "Notification", saved.getId().toString(), null,
                        objectMapper.writeValueAsString(saved), request.getRemoteAddr());
            }

            return saved;
        } catch (DataAccessException e) {
            throw new RuntimeException("Could not create broad notification", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public UserNotification createAutoGeneratedIndividualNotification(
            String title, String message, String type, String userId,
            String relatedEntityType, String relatedEntityId) {
        if (isBlank(title) || isBlank(message) || isBlank(userId)) {
            throw new IllegalArgumentException("Title, message, and user ID are required.");
        }

        Long schoolId = requireAuthenticatedSchool();
        NotificationEventCode eventCode = eventCodeFor(type);
        NotificationPublication publication = notificationPublisher.publish(new NotificationPublishRequest(
                schoolId, eventCode, categoryFor(eventCode), NotificationPriority.NORMAL,
                title, message, NotificationAudience.directUser(userId), relatedEntityType, relatedEntityId,
                null, null, securityUtil.getUsername(), null, null, Set.of(ExternalDeliveryChannel.PUSH)));
        return publication.recipients().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Direct notification did not produce an inbox recipient."));
    }

    /** Reading the inbox never creates or changes recipient rows. */
    @Transactional(readOnly = true)
    public Page<UserNotificationDTO> getNotificationsForUser(String userId, String userRole, Pageable pageable) {
        return getNotificationsForUser(userId, userRole, null, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<UserNotificationDTO> getNotificationsForUser(String userId, String userRole, Boolean isRead,
                                                              NotificationCategory category, Pageable pageable) {
        if (isBlank(userId) || isBlank(userRole)) return Page.empty(pageable);
        return userNotificationRepository
                .findInbox(userId, requireAuthenticatedSchool(), isRead, category, pageable)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public long getUnreadNotificationCount(String userId, String userRole) {
        if (isBlank(userId)) return 0;
        return userNotificationRepository.countByUserIdAndSchoolIdAndIsReadFalse(
                userId, requireAuthenticatedSchool());
    }

    @Transactional
    public void markAllNotificationsAsRead(String userId) {
        if (isBlank(userId)) return;
        userNotificationRepository.markAllRead(userId, requireAuthenticatedSchool());
    }

    @Transactional
    public void markNotificationAsRead(String userId, Long inboxId) {
        if (isBlank(userId) || inboxId == null) throw new IllegalArgumentException("User and notification are required.");
        UserNotification row = userNotificationRepository.findByIdAndUserIdAndSchoolId(
                        inboxId, userId, requireAuthenticatedSchool())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found."));
        row.markRead();
        userNotificationRepository.save(row);
    }

    @Transactional(readOnly = true)
    public Optional<Notification> getNotificationById(Long id) {
        return getNotificationById(id, null);
    }

    @Transactional(readOnly = true)
    public Optional<Notification> getNotificationById(Long id, Long requestedSchoolId) {
        if (id == null) return Optional.empty();
        return notificationRepository.findByIdAndSchoolId(id, resolveTargetSchoolId(requestedSchoolId));
    }

    @Transactional
    public Notification updateNotification(Long id, Notification updated, HttpServletRequest request) {
        return updateNotification(id, updated, null, request);
    }

    @Transactional
    public Notification updateNotification(Long id, Notification updated,
                                           Long requestedSchoolId, HttpServletRequest request) {
        if (id == null || updated == null) {
            throw new IllegalArgumentException("Notification ID and details must not be null.");
        }
        Long schoolId = resolveTargetSchoolId(requestedSchoolId);
        Notification existing = notificationRepository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found with ID: " + id));

        if (updated.getAudience() != null && !updated.getAudience().equalsIgnoreCase(existing.getAudience())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A published notification's audience cannot be changed. Publish a new notification instead.");
        }

        try {
            String oldValue = objectMapper.writeValueAsString(existing);
            existing.setTitle(updated.getTitle());
            existing.setMessage(updated.getMessage());
            existing.setType(updated.getType());
            existing.setCreatedBy(securityUtil.getUsername());
            Notification saved = notificationRepository.save(existing);
            auditService.logUpdate(
                    securityUtil.getUsername(), securityUtil.getRole(), "UPDATE_NOTIFICATION",
                    "Notification", id.toString(), oldValue,
                    objectMapper.writeValueAsString(saved), request.getRemoteAddr());
            return saved;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void deleteNotification(Long id, HttpServletRequest request) {
        deleteNotification(id, null, request);
    }

    @Transactional
    public void deleteNotification(Long id, Long requestedSchoolId, HttpServletRequest request) {
        if (id == null) throw new IllegalArgumentException("Notification ID must not be null.");
        Long schoolId = resolveTargetSchoolId(requestedSchoolId);
        Notification existing = notificationRepository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found with ID: " + id));
        try {
            String oldValue = objectMapper.writeValueAsString(existing);
            // user_notifications has no DB-level cascade from notifications (unlike
            // notification_deliveries, which cascades both directly and via user_notifications'
            // own FK) — delete the inbox rows first so the FK constraint doesn't reject the
            // notification delete. This only removes Edunexify's own records; it never touches
            // an email/push that was already sent to a recipient.
            userNotificationRepository.deleteByNotificationIdAndSchoolId(id, schoolId);
            notificationRepository.delete(existing);
            auditService.log(
                    securityUtil.getUsername(), securityUtil.getRole(), "DELETE_NOTIFICATION",
                    "Notification", id.toString(), oldValue, null, request.getRemoteAddr());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional(readOnly = true)
    public List<Notification> getAllBroadNotifications() {
        return getAllBroadNotifications(null);
    }

    @Transactional(readOnly = true)
    public List<Notification> getAllBroadNotifications(Long requestedSchoolId) {
        return notificationRepository
                .findBySchoolIdAndCreatedByIsNotNull(resolveTargetSchoolId(requestedSchoolId)).stream()
                .filter(n -> n.getAudience() != null)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<Notification> getAllNotifications(Pageable pageable) {
        return getAllNotifications(pageable, null);
    }

    /* Legacy immediate FCM audience dispatch removed in favor of durable recipient snapshots.
    // ─── FCM audience resolution ──────────────────────────────────────────────

    /**
     * Resolves the audience string to a list of userIds and dispatches FCM pushes.
     * Audience values mirror the frontend convention:
     *   "ALL"                   → every active student, linked parent and teacher
     *   "STUDENTS"              → every active student and their linked parents
     *   "TEACHERS"              → every teacher
     *   "CLASS:X"               → active students in class X
     *   "CLASS_WITH_TEACHER:X"  → active students in class X + class teacher of X
     *   <any other value>       → treated as a single userId
     */
    /*
    private void sendFcmForAudience(String audience, String title, String body) {
        if (audience == null || audience.isBlank()) return;

        try {
            Long schoolId = securityUtil.getSchoolId();
            if ("ALL".equalsIgnoreCase(audience)) {
                List<String> studentIds = studentRepository.findByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId)
                        .stream().map(Student::getStudentId).collect(Collectors.toList());
                List<String> teacherIds = teacherRepository.findByStatusAndSchoolId(TeacherStatus.ACTIVE, schoolId)
                        .stream().map(Teacher::getTeacherId).collect(Collectors.toList());
                fcmService.sendToUsers(studentIds, schoolId, title, body);
                fcmService.sendToUsers(teacherIds, schoolId, title, body);
                fcmService.sendToUsers(activeParentIdsForStudents(studentIds, schoolId), schoolId, title, body);

            } else if ("STUDENTS".equalsIgnoreCase(audience)) {
                List<String> ids = studentRepository.findByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId)
                        .stream().map(Student::getStudentId).collect(Collectors.toList());
                fcmService.sendToUsers(ids, schoolId, title, body);
                fcmService.sendToUsers(activeParentIdsForStudents(ids, schoolId), schoolId, title, body);

            } else if ("TEACHERS".equalsIgnoreCase(audience)) {
                List<String> ids = teacherRepository.findByStatusAndSchoolId(TeacherStatus.ACTIVE, schoolId)
                        .stream().map(Teacher::getTeacherId).collect(Collectors.toList());
                fcmService.sendToUsers(ids, schoolId, title, body);

            } else if (audience.toUpperCase().startsWith("CLASS:")) {
                String className = audience.substring("CLASS:".length());
                List<String> ids = studentRepository
                        .findByClassNameAndStatusAndSchoolId(className, StudentStatus.ACTIVE, schoolId)
                        .stream().map(Student::getStudentId).collect(Collectors.toList());
                fcmService.sendToUsers(ids, schoolId, title, body);
                fcmService.sendToUsers(activeParentIdsForStudents(ids, schoolId), schoolId, title, body);

            } else if (audience.toUpperCase().startsWith("CLASS_WITH_TEACHER:")) {
                String className = audience.substring("CLASS_WITH_TEACHER:".length());
                List<String> studentIds = studentRepository
                        .findByClassNameAndStatusAndSchoolId(className, StudentStatus.ACTIVE, schoolId)
                        .stream().map(Student::getStudentId).collect(Collectors.toList());
                fcmService.sendToUsers(studentIds, schoolId, title, body);
                fcmService.sendToUsers(activeParentIdsForStudents(studentIds, schoolId), schoolId, title, body);
                // A class can have more than one class-teacher once it has sections (one per
                // section) — notify every active one, not just "the" first result.
                teacherRepository.findByClassTeacherAndSchoolId(className, schoolId).stream()
                        .filter(t -> t.getStatus() == TeacherStatus.ACTIVE)
                        .forEach(t -> fcmService.sendToUser(t.getTeacherId(), schoolId, title, body));

            } else {
                // Treat as a specific userId
                fcmService.sendToUser(audience, schoolId, title, body);
            }
        } catch (Exception e) {
            log.warn("FCM audience dispatch failed for audience '{}': {}", audience, e.getMessage());
        }
    */
    @Transactional(readOnly = true)
    public Page<Notification> getAllNotifications(Pageable pageable, Long requestedSchoolId) {
        return notificationRepository.findBySchoolIdAndCreatedByIsNotNull(
                resolveTargetSchoolId(requestedSchoolId), pageable);
    }

    private UserNotificationDTO toDto(UserNotification row) {
        UserNotificationDTO dto = new UserNotificationDTO();
        dto.setId(row.getId());
        dto.setInboxId(row.getId());
        dto.setUserId(row.getUserId());
        dto.setIsRead(row.getIsRead());
        dto.setCreatedAt(row.getCreatedAt());
        dto.setReadAt(row.getReadAt());
        if (row.getNotification() != null) {
            dto.setTitle(row.getNotification().getTitle());
            dto.setMessage(row.getNotification().getMessage());
            dto.setType(row.getNotification().getType());
            dto.setEventCode(row.getNotification().getEventCode());
            dto.setCategory(row.getNotification().getCategory());
            dto.setPriority(row.getNotification().getPriority());
            dto.setSourceEntityType(row.getNotification().getSourceEntityType());
            dto.setSourceEntityId(row.getNotification().getSourceEntityId());
            dto.setActionRoute(row.getNotification().getActionRoute());
            dto.setActionMetadata(row.getNotification().getActionMetadata());
            dto.setExpiresAt(row.getNotification().getExpiresAt());
        }
        return dto;
    }

    private NotificationAudience audienceFor(String audience) {
        String value = audience.trim();
        String normalized = value.toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized)) return new NotificationAudience(NotificationAudienceType.WHOLE_SCHOOL, null);
        if ("STUDENTS".equals(normalized)) return new NotificationAudience(NotificationAudienceType.STUDENTS, null);
        if ("TEACHERS".equals(normalized)) return new NotificationAudience(NotificationAudienceType.TEACHERS, null);
        if ("PARENTS".equals(normalized)) return new NotificationAudience(NotificationAudienceType.PARENTS, null);
        if (normalized.startsWith("CLASS_WITH_TEACHER:")) return new NotificationAudience(
                NotificationAudienceType.CLASS_WITH_TEACHER, value.substring("CLASS_WITH_TEACHER:".length()).trim());
        if (normalized.startsWith("CLASS:")) return new NotificationAudience(
                NotificationAudienceType.CLASS, value.substring("CLASS:".length()).trim());
        if (normalized.startsWith("ROLE:")) return new NotificationAudience(
                NotificationAudienceType.ROLE, value.substring("ROLE:".length()).trim());
        return NotificationAudience.directUser(value);
    }

    private Set<ExternalDeliveryChannel> channelsFor(Notification notification) {
        if (notification.getChannel() == null) return Set.of(ExternalDeliveryChannel.PUSH);
        return switch (notification.getChannel().toUpperCase(Locale.ROOT)) {
            case "IN_APP" -> Set.of();
            case "EMAIL" -> Set.of(ExternalDeliveryChannel.EMAIL);
            case "BOTH" -> Set.of(ExternalDeliveryChannel.PUSH, ExternalDeliveryChannel.EMAIL);
            default -> Set.of(ExternalDeliveryChannel.PUSH);
        };
    }

    private NotificationEventCode eventCodeFor(Notification notification) {
        return notification.getEventCode() == null ? eventCodeFor(notification.getType()) : notification.getEventCode();
    }

    private NotificationEventCode eventCodeFor(String type) {
        if (type == null) return NotificationEventCode.LEGACY_NOTIFICATION;
        String normalized = type.toUpperCase(Locale.ROOT);
        if (normalized.contains("NOTICE")) return NotificationEventCode.NOTICE_PUBLISHED;
        if (normalized.contains("LEAVE") && normalized.contains("APPROV")) return NotificationEventCode.LEAVE_APPROVED;
        if (normalized.contains("LEAVE") && normalized.contains("REJECT")) return NotificationEventCode.LEAVE_REJECTED;
        if (normalized.contains("LEAVE")) return NotificationEventCode.LEAVE_SUBMITTED;
        if (normalized.contains("PAYMENT")) return NotificationEventCode.PAYMENT_SUCCESS;
        if (normalized.contains("FEE")) return NotificationEventCode.FEE_REMINDER;
        if (normalized.contains("ATTENDANCE")) return NotificationEventCode.ATTENDANCE_REMINDER;
        if (normalized.contains("SECURITY") || normalized.contains("PASSWORD")) return NotificationEventCode.ACCOUNT_SECURITY;
        return NotificationEventCode.LEGACY_NOTIFICATION;
    }

    private NotificationCategory categoryFor(Notification notification) {
        return notification.getCategory() == null ? categoryFor(eventCodeFor(notification)) : notification.getCategory();
    }

    private NotificationCategory categoryFor(NotificationEventCode code) {
        return switch (code) {
            case NOTICE_PUBLISHED -> NotificationCategory.NOTICE_ANNOUNCEMENT;
            case LEAVE_SUBMITTED, LEAVE_APPROVED, LEAVE_REJECTED, LEAVE_CANCELLED -> NotificationCategory.LEAVE;
            case PAYMENT_SUCCESS, PAYMENT_REFUNDED, FEE_DUE, FEE_OVERDUE, FEE_REMINDER -> NotificationCategory.FEES_PAYMENTS;
            case STUDENT_ABSENT, ATTENDANCE_LOW, ATTENDANCE_REMINDER -> NotificationCategory.ATTENDANCE;
            case REPORT_CARD_READY -> NotificationCategory.ACADEMICS_RESULTS;
            case EVENT_PUBLISHED, HOLIDAY_PUBLISHED -> NotificationCategory.EVENT_CALENDAR;
            case ACCOUNT_SECURITY -> NotificationCategory.ACCOUNT_SECURITY;
            default -> NotificationCategory.SYSTEM_ADMIN;
        };
    }

    private Long requireAuthenticatedSchool() {
        Long schoolId = securityUtil.getSchoolId();
        if (schoolId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An authenticated school context is required.");
        }
        return schoolId;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void cleanupOldNotifications() {
        // Intentional platform-wide retention job; public CRUD remains tenant-scoped.
        LocalDateTime oneMonthAgo = LocalDateTime.now().minus(Period.ofMonths(1));
        try {
            List<Notification> oldNotifications = notificationRepository.findByCreatedAtBefore(oneMonthAgo);
            notificationRepository.deleteAll(oldNotifications);
            log.info("Cleaned up {} old notifications created before {}", oldNotifications.size(), oneMonthAgo);
        } catch (DataAccessException e) {
            log.error("Data access error during scheduled notification cleanup", e);
        }
    }
}
