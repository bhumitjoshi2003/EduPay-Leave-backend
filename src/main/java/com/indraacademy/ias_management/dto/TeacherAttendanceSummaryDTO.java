package com.indraacademy.ias_management.dto;

import java.util.List;

public class TeacherAttendanceSummaryDTO {

    private int totalWorkingDays;
    private int presentDays;
    private int lateDays;
    private int absentDays;
    private int halfDayDays;
    private int onLeaveDays;
    private double onTimePercentage;
    private List<TeacherAttendanceResponse> records;

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

    public double getOnTimePercentage() { return onTimePercentage; }
    public void setOnTimePercentage(double onTimePercentage) { this.onTimePercentage = onTimePercentage; }

    public List<TeacherAttendanceResponse> getRecords() { return records; }
    public void setRecords(List<TeacherAttendanceResponse> records) { this.records = records; }
}
