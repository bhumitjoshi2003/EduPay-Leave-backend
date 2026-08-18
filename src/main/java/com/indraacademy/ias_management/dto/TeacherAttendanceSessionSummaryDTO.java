package com.indraacademy.ias_management.dto;

import java.util.List;

public class TeacherAttendanceSessionSummaryDTO {
    private String session;
    private String teacherId;
    private String teacherName;
    private int totalWorkingDays;
    private int presentDays;
    private int lateDays;
    private int absentDays;
    private int halfDayDays;
    private int onLeaveDays;
    private double attendancePercentage;
    private double onTimePercentage;
    private List<MonthlySummary> months;

    public static class MonthlySummary {
        private int month;
        private int year;
        private TeacherAttendanceSummaryDTO summary;

        public MonthlySummary(int month, int year, TeacherAttendanceSummaryDTO summary) {
            this.month = month;
            this.year = year;
            this.summary = summary;
        }

        public int getMonth() { return month; }
        public int getYear() { return year; }
        public TeacherAttendanceSummaryDTO getSummary() { return summary; }
    }

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }
    public String getTeacherId() { return teacherId; }
    public void setTeacherId(String teacherId) { this.teacherId = teacherId; }
    public String getTeacherName() { return teacherName; }
    public void setTeacherName(String teacherName) { this.teacherName = teacherName; }
    public int getTotalWorkingDays() { return totalWorkingDays; }
    public void setTotalWorkingDays(int totalWorkingDays) { this.totalWorkingDays = totalWorkingDays; }
    public int getPresentDays() { return presentDays; }
    public void setPresentDays(int presentDays) { this.presentDays = presentDays; }
    public int getLateDays() { return lateDays; }
    public void setLateDays(int lateDays) { this.lateDays = lateDays; }
    public int getAbsentDays() { return absentDays; }
    public void setAbsentDays(int absentDays) { this.absentDays = absentDays; }
    public int getHalfDayDays() { return halfDayDays; }
    public void setHalfDayDays(int halfDayDays) { this.halfDayDays = halfDayDays; }
    public int getOnLeaveDays() { return onLeaveDays; }
    public void setOnLeaveDays(int onLeaveDays) { this.onLeaveDays = onLeaveDays; }
    public double getAttendancePercentage() { return attendancePercentage; }
    public void setAttendancePercentage(double attendancePercentage) { this.attendancePercentage = attendancePercentage; }
    public double getOnTimePercentage() { return onTimePercentage; }
    public void setOnTimePercentage(double onTimePercentage) { this.onTimePercentage = onTimePercentage; }
    public List<MonthlySummary> getMonths() { return months; }
    public void setMonths(List<MonthlySummary> months) { this.months = months; }
}
