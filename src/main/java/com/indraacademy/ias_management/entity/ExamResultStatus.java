package com.indraacademy.ias_management.entity;

/**
 * Result lifecycle of one exam (V82). DRAFT: staff can enter and review marks; students and
 * parents see nothing. PUBLISHED: students and parents can see the result, and marks are locked
 * until an admin unpublishes.
 */
public enum ExamResultStatus {
    DRAFT,
    PUBLISHED
}
