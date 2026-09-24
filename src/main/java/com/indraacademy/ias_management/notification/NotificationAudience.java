package com.indraacademy.ias_management.notification;

public record NotificationAudience(NotificationAudienceType type, String value) {
    public NotificationAudience {
        if (type == null) throw new IllegalArgumentException("Notification audience type is required.");
    }

    public static NotificationAudience directUser(String userId) {
        return new NotificationAudience(NotificationAudienceType.DIRECT_USER, userId);
    }

    /**
     * Students only (no parents) actively enrolled today in one class of one session — the whole
     * class when sectionId is null, otherwise that section. Resolved from student_enrollment.
     */
    public static NotificationAudience classSectionStudents(long academicSessionId, long classId, Long sectionId) {
        return new NotificationAudience(NotificationAudienceType.CLASS_SECTION_STUDENTS,
                academicSessionId + ":" + classId + ":" + (sectionId == null ? "*" : sectionId));
    }

    public static NotificationAudience studentWithParents(String studentId,
                                                          NotificationAudienceType permissionAudience) {
        if (permissionAudience != NotificationAudienceType.STUDENT_WITH_LEAVE_PARENTS
                && permissionAudience != NotificationAudienceType.STUDENT_WITH_FEE_PARENTS
                && permissionAudience != NotificationAudienceType.STUDENT_WITH_ATTENDANCE_PARENTS
                && permissionAudience != NotificationAudienceType.STUDENT_WITH_RESULT_PARENTS) {
            throw new IllegalArgumentException("A permission-aware student/parent audience is required.");
        }
        return new NotificationAudience(permissionAudience, studentId);
    }
}
