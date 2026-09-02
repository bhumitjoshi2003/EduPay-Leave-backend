package com.indraacademy.ias_management.notification;

public record NotificationAudience(NotificationAudienceType type, String value) {
    public NotificationAudience {
        if (type == null) throw new IllegalArgumentException("Notification audience type is required.");
    }

    public static NotificationAudience directUser(String userId) {
        return new NotificationAudience(NotificationAudienceType.DIRECT_USER, userId);
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
