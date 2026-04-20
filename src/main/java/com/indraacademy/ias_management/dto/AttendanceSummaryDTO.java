package com.indraacademy.ias_management.dto;

import java.util.List;

public class AttendanceSummaryDTO {

    private String studentId;
    private String studentName;
    private String className;
    private long totalWorkingDays;
    private long daysPresent;
    private long daysAbsent;
    private double attendancePercentage;
    /** Populated only when type=year; null for type=month. */
    private List<MonthlyBreakdown> monthlyBreakdown;

    public static class MonthlyBreakdown {
        private String month;
        private int year;
        private long workingDays;
        private long present;
        private long absent;
        private double percentage;

        public MonthlyBreakdown(String month, int year, long workingDays, long present, long absent, double percentage) {
            this.month = month;
            this.year = year;
            this.workingDays = workingDays;
            this.present = present;
            this.absent = absent;
            this.percentage = percentage;
        }

        public String getMonth() { return month; }
        public int getYear() { return year; }
        public long getWorkingDays() { return workingDays; }
        public long getPresent() { return present; }
        public long getAbsent() { return absent; }
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

    public double getAttendancePercentage() { return attendancePercentage; }
    public void setAttendancePercentage(double attendancePercentage) { this.attendancePercentage = attendancePercentage; }

    public List<MonthlyBreakdown> getMonthlyBreakdown() { return monthlyBreakdown; }
    public void setMonthlyBreakdown(List<MonthlyBreakdown> monthlyBreakdown) { this.monthlyBreakdown = monthlyBreakdown; }
}
