package com.indraacademy.ias_management.dto;

public class ClassStatsDto {
    private String className;
    private long studentCount;
    private double attendanceRate;
    /** Working days counted for attendanceRate. 0 means no attendance has been marked yet this month — distinct from a genuine 0% rate. */
    private long workingDays;

    public ClassStatsDto(String className, long studentCount, double attendanceRate, long workingDays) {
        this.className = className;
        this.studentCount = studentCount;
        this.attendanceRate = attendanceRate;
        this.workingDays = workingDays;
    }

    public String getClassName() { return className; }
    public long getStudentCount() { return studentCount; }
    public double getAttendanceRate() { return attendanceRate; }
    public long getWorkingDays() { return workingDays; }
}
