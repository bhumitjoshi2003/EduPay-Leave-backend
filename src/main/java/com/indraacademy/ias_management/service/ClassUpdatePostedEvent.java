package com.indraacademy.ias_management.service;

/** Published inside the create transaction; notified only after it commits. */
public record ClassUpdatePostedEvent(long updateId, long schoolId, long academicSessionId, long classId,
                                     Long sectionId, String className, String sectionName, String subjectName,
                                     String teacherId) {}
