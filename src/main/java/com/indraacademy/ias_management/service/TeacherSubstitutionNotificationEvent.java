package com.indraacademy.ias_management.service;

import java.time.LocalDate;

public record TeacherSubstitutionNotificationEvent(
        Long schoolId, Long substitutionId, long revision, String recipientTeacherId,
        String eventKind, String className, String sectionName, String subjectName,
        Integer periodNumber, String startTime, String endTime, String originalTeacherName,
        LocalDate date, String actorUserId) {}
