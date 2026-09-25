package com.indraacademy.ias_management.dto;

import java.util.List;

/**
 * Attendance V2 figures for one student over a period. Counts come from explicit
 * student_attendance rows only: totalWorkingDays = submitted attendance days that included the
 * student, daysPresent/daysAbsent = PRESENT/ABSENT rows, approvedLeaveDays = the ABSENT days that
 * also have APPROVED leave (informational — they still count as absent), and
 * attendancePercentage = daysPresent / totalWorkingDays * 100 (0 when no days were submitted).
 */
public class AttendanceSummaryDTO {
    private String studentId;
    private String studentName;
    private String className;
    private long totalWorkingDays;
    private long daysPresent;
    private long daysAbsent;
    private long approvedLeaveDays;
    private double attendancePercentage;
    /** Only populated for a session ("year") summary: one entry per academic month. */
    private List<MonthlyBreakdown> monthlyBreakdown;

    public static class MonthlyBreakdown {
        private String month;
        private int year;
        private long workingDays;
        private long present;
        private long absent;
        private long approvedLeave;
        private double percentage;

        public MonthlyBreakdown(String month, int year, long workingDays, long present, long absent,
                                long approvedLeave, double percentage) {
            this.month = month;
            this.year = year;
            this.workingDays = workingDays;
            this.present = present;
            this.absent = absent;
            this.approvedLeave = approvedLeave;
            this.percentage = percentage;
        }

        public String getMonth() { return month; }
        public int getYear() { return year; }
        public long getWorkingDays() { return workingDays; }
        public long getPresent() { return present; }
        public long getAbsent() { return absent; }
        public long getApprovedLeave() { return approvedLeave; }
        public double getPercentage() { return percentage; }
    }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public long getTotalWorkingDays() { return totalWorkingDays; }
    public void setTotalWorkingDays(long totalWorkingDays) { this.totalWorkingDays = totalWorkingDays; }
    public long getDaysPresent() { return daysPresent; }
    public void setDaysPresent(long daysPresent) { this.daysPresent = daysPresent; }
    public long getDaysAbsent() { return daysAbsent; }
    public void setDaysAbsent(long daysAbsent) { this.daysAbsent = daysAbsent; }
    public long getApprovedLeaveDays() { return approvedLeaveDays; }
    public void setApprovedLeaveDays(long approvedLeaveDays) { this.approvedLeaveDays = approvedLeaveDays; }
    public double getAttendancePercentage() { return attendancePercentage; }
    public void setAttendancePercentage(double attendancePercentage) { this.attendancePercentage = attendancePercentage; }
    public List<MonthlyBreakdown> getMonthlyBreakdown() { return monthlyBreakdown; }
    public void setMonthlyBreakdown(List<MonthlyBreakdown> monthlyBreakdown) { this.monthlyBreakdown = monthlyBreakdown; }
}
