package com.indraacademy.ias_management.dto;

public class AttendanceTrendDto {

    private String period;
    private double attendanceRate;

    public AttendanceTrendDto(String period, double attendanceRate) {
        this.period = period;
        this.attendanceRate = attendanceRate;
    }

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }

    public double getAttendanceRate() { return attendanceRate; }
    public void setAttendanceRate(double attendanceRate) { this.attendanceRate = attendanceRate; }
}
