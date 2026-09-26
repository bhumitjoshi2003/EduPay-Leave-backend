package com.indraacademy.ias_management.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only Attendance Insights (Phase 1) over Attendance V2. Every percentage is
 * PRESENT days / submitted days * 100 (one decimal, 0 with no data); approved leave is still an
 * absence and stays in the denominator — approvedLeave counts are informational only.
 */
public final class AttendanceInsightsDtos {
    private AttendanceInsightsDtos() {}

    /** One month of a student's session, present only when that month has submitted days. */
    public record MonthTrend(int year, int month, String label, long submittedDays, long present, long absent,
                             long approvedLeave, double percentage) {}

    /** One submitted day, most recent first. */
    public record RecentDay(LocalDate date, String status, boolean approvedLeave) {}

    public record StudentInsights(String studentId, String studentName, String className, String sessionLabel,
                                  LocalDate from, LocalDate to, long submittedDays, long present, long absent,
                                  long approvedLeave, double percentage, double lowAttendanceThreshold,
                                  boolean lowAttendance, int currentAbsenceStreak, List<MonthTrend> monthlyTrend,
                                  List<RecentDay> recent) {}

    /** One currently enrolled student of the class/section. lowAttendance needs at least one submitted day. */
    public record StudentRow(String studentId, String studentName, String sectionName, long submittedDays,
                             long present, long absent, long approvedLeave, double percentage,
                             boolean lowAttendance, int currentAbsenceStreak, int streakApprovedLeaveDays) {}

    /**
     * A class (sectionId null = every section) or one section: weighted percentage over all its
     * students' submitted rows, how many are below the threshold, how many have a current absence
     * streak of at least streakThreshold days, and every student, low attendance first.
     */
    public record ClassInsights(Long classId, String className, Long sectionId, String sectionName, String sessionLabel,
                                LocalDate from, LocalDate to, double lowAttendanceThreshold, int streakThreshold,
                                int totalStudents, long submittedRecords, double classPercentage,
                                int belowThresholdCount, int consecutiveAbsenceCount, List<StudentRow> students) {}
}
