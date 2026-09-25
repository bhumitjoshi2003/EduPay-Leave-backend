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
 * One publication per scheduled (or materially rescheduled) assessment, fanned out by the
 * existing pipeline to the class/section's actively enrolled students: in-app inbox + FCM push.
 * No email. A notification failure never undoes the save (after-commit, swallowed and logged).
 */
@Component
public class AssessmentNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(AssessmentNotificationListener.class);

    private final BusinessNotificationService notifications;

    public AssessmentNotificationListener(BusinessNotificationService notifications) {
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(AssessmentNotificationEvent event) {
        boolean scheduled = event.kind() == AssessmentNotificationEvent.Kind.SCHEDULED;
        try {
            notifications.publish(event.schoolId(),
                    scheduled ? NotificationEventCode.ASSESSMENT_SCHEDULED : NotificationEventCode.ASSESSMENT_UPDATED,
                    NotificationCategory.ACADEMICS_RESULTS,
                    scheduled ? AssessmentNotifications.scheduledTitle(event.subjectName(), event.type())
                              : AssessmentNotifications.updatedTitle(event.subjectName(), event.type()),
                    scheduled ? AssessmentNotifications.scheduledBody(event.subjectName(), event.type(),
                                        event.assessmentDate(), event.startTime())
                              : AssessmentNotifications.updatedBody(event.title(), event.assessmentDate(), event.startTime()),
                    NotificationAudience.classSectionStudents(event.academicSessionId(), event.classId(), event.sectionId()),
                    "Assessment", String.valueOf(event.assessmentId()), AssessmentNotifications.ACTION_ROUTE,
                    event.actorUserId(),
                    scheduled ? "assessment:" + event.assessmentId() + ":scheduled"
                              : "assessment:" + event.assessmentId() + ":updated:" + event.revision(),
                    Set.of(ExternalDeliveryChannel.PUSH));
        } catch (RuntimeException failure) {
            log.error("Assessment {} was saved but notifying class {} failed", event.assessmentId(), event.classId(), failure);
        }
    }
}
