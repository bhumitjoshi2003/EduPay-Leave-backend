package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AssessmentType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Notification wording for assessments (scheduled / updated / reminder). */
final class AssessmentNotifications {
    static final String ACTION_ROUTE = "/dashboard/assessments";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private AssessmentNotifications() {}

    /** "Science Unit Test"; OTHER reads "Science Assessment". */
    static String name(String subject, AssessmentType type) {
        return subject + " " + type.label();
    }

    /** "30 Sep" or "30 Sep, 10:00 AM". */
    static String when(LocalDate date, LocalTime start) {
        return date.format(DATE) + (start == null ? "" : ", " + start.format(TIME));
    }

    static String scheduledTitle(String subject, AssessmentType type) {
        return "Upcoming " + name(subject, type);
    }

    static String scheduledBody(String subject, AssessmentType type, LocalDate date, LocalTime start) {
        return name(subject, type) + " scheduled for " + when(date, start) + ".";
    }

    static String updatedTitle(String subject, AssessmentType type) {
        return "Updated: " + name(subject, type);
    }

    static String updatedBody(String title, LocalDate date, LocalTime start) {
        return title + " is now scheduled for " + when(date, start) + ".";
    }

    static String reminderTitle(String subject, AssessmentType type) {
        return "Tomorrow: " + name(subject, type);
    }

    static String reminderBody(String title, LocalTime start) {
        return title + " is tomorrow" + (start == null ? "" : " at " + start.format(TIME)) + ". All the best!";
    }
}
