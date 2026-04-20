package com.indraacademy.ias_management.dto;

public class ClassAttendanceSummaryDTO {

    private String studentId;
    private String studentName;
    private long totalWorkingDays;
    private long daysPresent;
    private long daysAbsent;
    private double attendancePercentage;

    public ClassAttendanceSummaryDTO(String studentId, String studentName,
                                     long totalWorkingDays, long daysPresent,
                                     long daysAbsent, double attendancePercentage) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.totalWorkingDays = totalWorkingDays;
        this.daysPresent = daysPresent;
        this.daysAbsent = daysAbsent;
        this.attendancePercentage = attendancePercentage;
    }

    public String getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public long getTotalWorkingDays() { return totalWorkingDays; }
    public long getDaysPresent() { return daysPresent; }
    public long getDaysAbsent() { return daysAbsent; }
    public double getAttendancePercentage() { return attendancePercentage; }
}
