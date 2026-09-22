package com.indraacademy.ias_management.dto;

import java.util.List;

public record StaffAdoptionReminderPreviewResponse(int count, List<TeacherSummary> teachers) {
    public record TeacherSummary(String teacherId, String name) {}
}
