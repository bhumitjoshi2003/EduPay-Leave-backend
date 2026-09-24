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

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * One publication per new post, fanned out by the existing pipeline to the class/section's
 * actively enrolled students: in-app inbox + FCM push. No email in Phase 1. A notification
 * failure never undoes the post (after-commit, swallowed and logged).
 */
@Component
public class HomeworkClassworkNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(HomeworkClassworkNotificationListener.class);
    static final String ACTION_ROUTE = "/dashboard/homework";
    private static final DateTimeFormatter DUE_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final BusinessNotificationService notifications;

    public HomeworkClassworkNotificationListener(BusinessNotificationService notifications) {
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(HomeworkClassworkPostedEvent event) {
        try {
            notifications.publish(event.schoolId(), NotificationEventCode.HOMEWORK_CLASSWORK_POSTED,
                    NotificationCategory.ACADEMICS_RESULTS, title(event), message(event),
                    NotificationAudience.classSectionStudents(event.academicSessionId(), event.classId(), event.sectionId()),
                    "HomeworkClasswork", String.valueOf(event.workId()), ACTION_ROUTE, event.teacherId(),
                    "homework-classwork:" + event.workId() + ":posted",
                    Set.of(ExternalDeliveryChannel.PUSH));
        } catch (RuntimeException failure) {
            log.error("Homework/classwork {} was saved but notifying class {} failed", event.workId(), event.classId(), failure);
        }
    }

    static String kind(HomeworkClassworkPostedEvent event) {
        if (event.hasClasswork() && event.hasHomework()) return "Classwork & Homework";
        return event.hasHomework() ? "Homework" : "Classwork";
    }

    static String title(HomeworkClassworkPostedEvent event) {
        return "New " + event.subjectName() + " " + kind(event);
    }

    static String message(HomeworkClassworkPostedEvent event) {
        String kind = kind(event);
        String verb = event.hasClasswork() && event.hasHomework() ? "have" : "has";
        String classLabel = "Class " + event.className()
                + (event.sectionName() == null || event.sectionName().isBlank() ? "" : " " + event.sectionName());
        String message = kind + " " + verb + " been posted for " + classLabel + ".";
        if (event.hasHomework() && event.dueDate() != null) {
            if (event.dueDate().equals(event.workDate())) message += " Due today.";
            else if (event.dueDate().equals(event.workDate().plusDays(1))) message += " Due tomorrow.";
            else message += " Due " + event.dueDate().format(DUE_FORMAT) + ".";
        }
        return message;
    }
}
