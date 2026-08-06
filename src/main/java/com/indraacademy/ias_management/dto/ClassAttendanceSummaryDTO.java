package com.indraacademy.ias_management.dto;

public class ClassAttendanceSummaryDTO {

    private String studentId;
    private String studentName;
    /** Which class this row belongs to. Always set; mainly useful when rows from multiple classes are flattened together (see /summary/school). */
    private String className;
    private long totalWorkingDays;
    private long daysPresent;
    private long daysAbsent;
    private double attendancePercentage;

    public ClassAttendanceSummaryDTO(String studentId, String studentName, String className,
                                     long totalWorkingDays, long daysPresent,
                                     long daysAbsent, double attendancePercentage) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.className = className;
        this.totalWorkingDays = totalWorkingDays;
        this.daysPresent = daysPresent;
        this.daysAbsent = daysAbsent;
        this.attendancePercentage = attendancePercentage;
    }

    public String getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public String getClassName() { return className; }
    public long getTotalWorkingDays() { return totalWorkingDays; }
    public long getDaysPresent() { return daysPresent; }
    public long getDaysAbsent() { return daysAbsent; }
    public double getAttendancePercentage() { return attendancePercentage; }
}
