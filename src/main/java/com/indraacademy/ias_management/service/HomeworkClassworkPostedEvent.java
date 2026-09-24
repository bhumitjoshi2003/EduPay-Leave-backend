package com.indraacademy.ias_management.service;

import java.time.LocalDate;

/** Published inside the create transaction; notified only after it commits. */
public record HomeworkClassworkPostedEvent(long workId, long schoolId, long academicSessionId, long classId,
                                           Long sectionId, String className, String sectionName,
                                           String subjectName, boolean hasClasswork, boolean hasHomework,
                                           LocalDate workDate, LocalDate dueDate, String teacherId) {}
