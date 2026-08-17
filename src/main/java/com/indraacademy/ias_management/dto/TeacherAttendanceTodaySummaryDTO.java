package com.indraacademy.ias_management.dto;

import java.time.LocalDate;

/**
 * Compact today-only staff attendance counts for the admin dashboard — a lighter sibling of
 * {@link TeacherAttendanceSummaryDTO} (which is month-scoped and includes the full per-day record
 * list). Built from the same {@code TeacherAttendanceService.getAttendanceByDate} call the daily
 * Staff Attendance page already uses, so it inherits that method's calendar/holiday/leave-aware
 * resolution rather than re-deriving counts independently.
 *
 * <p>Unlike {@link TeacherAttendanceSummaryDTO#getPresentDays()} (ON_TIME + LATE combined),
 * {@code presentCount} here is ON_TIME only — Late is reported as its own bucket, matching how the
 * dashboard widget presents the two separately.
 */
public class TeacherAttendanceTodaySummaryDTO {

    private LocalDate date;
    private int presentCount;
    private int lateCount;
    private int absentCount;
    private int halfDayCount;
    private int onLeaveCount;

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public int getPresentCount() { return presentCount; }
    public void setPresentCount(int presentCount) { this.presentCount = presentCount; }

    public int getLateCount() { return lateCount; }
    public void setLateCount(int lateCount) { this.lateCount = lateCount; }

    public int getAbsentCount() { return absentCount; }
    public void setAbsentCount(int absentCount) { this.absentCount = absentCount; }

    public int getHalfDayCount() { return halfDayCount; }
    public void setHalfDayCount(int halfDayCount) { this.halfDayCount = halfDayCount; }

    public int getOnLeaveCount() { return onLeaveCount; }
    public void setOnLeaveCount(int onLeaveCount) { this.onLeaveCount = onLeaveCount; }
}
