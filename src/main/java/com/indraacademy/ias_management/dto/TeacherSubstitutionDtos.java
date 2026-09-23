package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.TeacherSubstitution;
import com.indraacademy.ias_management.entity.TeacherSubstitutionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class TeacherSubstitutionDtos {
    private TeacherSubstitutionDtos() {}

    public record UpsertRequest(@NotNull Long timetableEntryId, @NotNull LocalDate date,
                                @NotBlank String substituteTeacherId) {}
    public record ChangeRequest(@NotBlank String substituteTeacherId) {}
    public record FreeTeacher(String teacherId, String name) {}
    public record Assignment(Long id, long revision, LocalDate date, Long timetableEntryId,
                             String originalTeacherId, String originalTeacherName,
                             String substituteTeacherId, String substituteTeacherName,
                             String className, String sectionName, String subjectName,
                             Integer periodNumber, String startTime, String endTime,
                             TeacherSubstitutionStatus status, String assignedBy,
                             LocalDateTime assignedAt, LocalDateTime updatedAt) {
        public static Assignment from(TeacherSubstitution s) {
            return new Assignment(s.getId(), s.getRevision(), s.getDate(), s.getTimetableEntryId(),
                    s.getOriginalTeacherId(), s.getOriginalTeacherName(), s.getSubstituteTeacherId(),
                    s.getSubstituteTeacherName(), s.getClassName(), s.getSectionName(), s.getSubjectName(),
                    s.getPeriodNumber(), s.getStartTime(), s.getEndTime(), s.getStatus(), s.getAssignedBy(),
                    s.getAssignedAt(), s.getUpdatedAt());
        }
    }
    public record UncoveredPeriod(Long timetableEntryId, String originalTeacherId, String originalTeacherName,
                                  String className, String sectionName, String subjectName, Integer periodNumber,
                                  String startTime, String endTime, Assignment assignment,
                                  List<FreeTeacher> freeTeachers) {}
}
