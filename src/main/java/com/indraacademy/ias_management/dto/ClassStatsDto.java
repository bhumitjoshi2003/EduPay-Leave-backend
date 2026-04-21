package com.indraacademy.ias_management.dto;

public class ClassStatsDto {
    private String className;
    private long studentCount;
    private double attendanceRate;

    public ClassStatsDto(String className, long studentCount, double attendanceRate) {
        this.className = className;
        this.studentCount = studentCount;
        this.attendanceRate = attendanceRate;
    }

    public String getClassName() { return className; }
    public long getStudentCount() { return studentCount; }
    public double getAttendanceRate() { return attendanceRate; }
}
