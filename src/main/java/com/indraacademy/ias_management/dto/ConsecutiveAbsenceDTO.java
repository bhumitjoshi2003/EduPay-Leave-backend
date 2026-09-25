package com.indraacademy.ias_management.dto;

import java.util.List;

/**
 * A student whose most recent submitted attendance days in their class/section were all ABSENT
 * — the "absent for the last 3 days" pattern, as opposed to a low cumulative percentage.
 * Approved leave still counts as absent (raw attendance); approvedLeaveDates lists which of the
 * streak's days were covered by APPROVED leave, so callers can tell unexplained absence apart.
 * Cumulative session figures come from the same calculation as the class summary.
 */
public class ConsecutiveAbsenceDTO {
    private String studentId;
    private String studentName;
    private String className;
    /** Length of the current streak of ABSENT submitted days. */
    private int consecutiveAbsentDays;
    /** The streak's dates (yyyy-MM-dd), oldest first. */
    private List<String> absentDates;
    /** The subset of absentDates covered by APPROVED leave. */
    private List<String> approvedLeaveDates;
    private long totalWorkingDays;
    private long daysPresent;
    private long daysAbsent;
    private double attendancePercentage;

    public ConsecutiveAbsenceDTO(String studentId, String studentName, String className,
                                 int consecutiveAbsentDays, List<String> absentDates, List<String> approvedLeaveDates,
                                 long totalWorkingDays, long daysPresent, long daysAbsent,
                                 double attendancePercentage) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.className = className;
        this.consecutiveAbsentDays = consecutiveAbsentDays;
        this.absentDates = absentDates;
        this.approvedLeaveDates = approvedLeaveDates;
        this.totalWorkingDays = totalWorkingDays;
        this.daysPresent = daysPresent;
        this.daysAbsent = daysAbsent;
        this.attendancePercentage = attendancePercentage;
    }

    public String getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public String getClassName() { return className; }
    public int getConsecutiveAbsentDays() { return consecutiveAbsentDays; }
    public List<String> getAbsentDates() { return absentDates; }
    public List<String> getApprovedLeaveDates() { return approvedLeaveDates; }
    public long getTotalWorkingDays() { return totalWorkingDays; }
    public long getDaysPresent() { return daysPresent; }
    public long getDaysAbsent() { return daysAbsent; }
    public double getAttendancePercentage() { return attendancePercentage; }
}
