package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

@Component
public class TeacherSubstitutionNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(TeacherSubstitutionNotificationListener.class);
    private final BusinessNotificationService notifications;

    public TeacherSubstitutionNotificationListener(BusinessNotificationService notifications) {
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(TeacherSubstitutionNotificationEvent event) {
        try {
            boolean assigned = "ASSIGNED".equals(event.eventKind());
            String classLabel = event.sectionName() == null || event.sectionName().isBlank()
                    ? "Class " + event.className() : "Class " + event.className() + " " + event.sectionName();
            String title = assigned ? "Substitution Assigned" : "Substitution Cancelled";
            String message = assigned
                    ? String.format("You have been assigned %s, Period %d on %s, replacing %s.",
                        classLabel, event.periodNumber(), event.date(), event.originalTeacherName())
                    : String.format("Your cover for %s, Period %d on %s has been cancelled or reassigned.",
                        classLabel, event.periodNumber(), event.date());
            notifications.direct(event.schoolId(), event.recipientTeacherId(),
                    assigned ? NotificationEventCode.TEACHER_SUBSTITUTION_ASSIGNED
                            : NotificationEventCode.TEACHER_SUBSTITUTION_CANCELLED,
                    NotificationCategory.ACADEMICS_RESULTS, title, message,
                    "TeacherSubstitution", String.valueOf(event.substitutionId()),
                    "/dashboard/teacher-dashboard", event.actorUserId(),
                    "teacher-substitution:" + event.substitutionId() + ":" + event.revision() + ":"
                            + event.eventKind() + ":" + event.recipientTeacherId(),
                    Set.of(ExternalDeliveryChannel.PUSH));
        } catch (RuntimeException failure) {
            log.error("Substitution {} persisted but notification {} to teacher {} failed",
                    event.substitutionId(), event.eventKind(), event.recipientTeacherId(), failure);
        }
    }
}
