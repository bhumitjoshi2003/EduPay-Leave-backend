package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AssessmentType;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Published inside the create/update transaction; notified only after it commits. UPDATED is
 * only published when the date, time or title changed. revision makes each update's
 * notification distinct (idempotency key) without ever duplicating the same one.
 */
public record AssessmentNotificationEvent(Kind kind, long assessmentId, long revision, long schoolId,
                                          long academicSessionId, long classId, Long sectionId,
                                          String subjectName, AssessmentType type, String title,
                                          LocalDate assessmentDate, LocalTime startTime, String actorUserId) {
    public enum Kind { SCHEDULED, UPDATED }
}
