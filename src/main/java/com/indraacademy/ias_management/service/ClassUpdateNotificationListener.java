package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationAudience;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

/**
 * One publication per new class update, fanned out by the existing pipeline to the class/section's
 * actively enrolled students: in-app inbox + FCM push. No email in Phase 1. A notification
 * failure never undoes the update (after-commit, swallowed and logged).
 */
@Component
public class ClassUpdateNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(ClassUpdateNotificationListener.class);
    static final String ACTION_ROUTE = "/dashboard/class-updates";
    static final String MESSAGE = "A new class update has been posted.";

    private final BusinessNotificationService notifications;

    public ClassUpdateNotificationListener(BusinessNotificationService notifications) {
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(ClassUpdatePostedEvent event) {
        try {
            notifications.publish(event.schoolId(), NotificationEventCode.CLASS_UPDATE_POSTED,
                    NotificationCategory.NOTICE_ANNOUNCEMENT, title(event), MESSAGE,
                    NotificationAudience.classSectionStudents(event.academicSessionId(), event.classId(), event.sectionId()),
                    "ClassUpdate", String.valueOf(event.updateId()), ACTION_ROUTE, event.teacherId(),
                    "class-update:" + event.updateId() + ":posted",
                    Set.of(ExternalDeliveryChannel.PUSH));
        } catch (RuntimeException failure) {
            log.error("Class update {} was saved but notifying class {} failed", event.updateId(), event.classId(), failure);
        }
    }

    /** "Class Update — Science", or the class label for a class-wide update. */
    static String title(ClassUpdatePostedEvent event) {
        if (event.subjectName() != null && !event.subjectName().isBlank()) {
            return "Class Update — " + event.subjectName();
        }
        return "Class Update — Class " + event.className()
                + (event.sectionName() == null || event.sectionName().isBlank() ? "" : " " + event.sectionName());
    }
}
