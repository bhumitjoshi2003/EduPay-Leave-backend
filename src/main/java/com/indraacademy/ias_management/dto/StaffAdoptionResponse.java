package com.indraacademy.ias_management.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public record StaffAdoptionResponse(Summary summary, List<TeacherRow> teachers) {
    public record Summary(
            long totalTeachers,
            long startedTeachers,
            long notStartedTeachers,
            long attendanceUsedTeachers,
            long disabledTeachers) {}

    public record TeacherRow(
            String teacherId,
            String name,
            String accountStatus,
            Instant lastActiveAt,
            boolean hasUsedAttendance,
            LocalDateTime lastAttendanceAt) {}
}
