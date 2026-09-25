package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.AttendanceStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Attendance V2 marking contract. The server decides school, session, roster and authorization. */
public final class AttendanceSheetDtos {
    private AttendanceSheetDtos() {}

    /** One student's final status in a submission. */
    public record StudentStatus(String studentId, AttendanceStatus status) {}

    /**
     * A full submission for one class (section) day: an explicit status for exactly every
     * eligible student. A TEACHER may omit classId/sectionId — their own class-teacher scope is used.
     */
    public record SubmitRequest(Long classId, Long sectionId, LocalDate date, List<StudentStatus> students) {}

    /**
     * status is the saved status (null until attendance is submitted for the day);
     * approvedLeave is true only for APPROVED leave on that date (pending/rejected never show).
     */
    public record SheetStudent(String studentId, String name, AttendanceStatus status, boolean approvedLeave) {}

    /**
     * The roster for one class (section) day. markable is false (with blockedReason) when the date
     * cannot be marked — future, outside the current session, a non-working weekday or a holiday.
     */
    public record SheetView(Long classId, String className, Long sectionId, String sectionName, LocalDate date,
                            boolean submitted, String markedBy, LocalDateTime markedAt, LocalDateTime updatedAt,
                            boolean markable, String blockedReason, List<SheetStudent> students) {}
}
