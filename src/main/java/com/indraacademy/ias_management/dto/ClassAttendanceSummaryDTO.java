package com.indraacademy.ias_management.dto;

/**
 * One student's Attendance V2 figures within a class (or a school-wide list). Same formula as
 * {@link AttendanceSummaryDTO}: percentage = daysPresent / totalWorkingDays * 100, with
 * approvedLeaveDays informational only.
 */
public class ClassAttendanceSummaryDTO {
    private String studentId;
    private String studentName;
    /** The class this row belongs to (the student's latest class in the period for school-wide lists). */
    private String className;
    private long totalWorkingDays;
    private long daysPresent;
    private long daysAbsent;
    private long approvedLeaveDays;
    private double attendancePercentage;

    public ClassAttendanceSummaryDTO(String studentId, String studentName, String className,
                                     long totalWorkingDays, long daysPresent, long daysAbsent,
                                     long approvedLeaveDays, double attendancePercentage) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.className = className;
        this.totalWorkingDays = totalWorkingDays;
        this.daysPresent = daysPresent;
        this.daysAbsent = daysAbsent;
        this.approvedLeaveDays = approvedLeaveDays;
        this.attendancePercentage = attendancePercentage;
    }

    public String getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public String getClassName() { return className; }
    public long getTotalWorkingDays() { return totalWorkingDays; }
    public long getDaysPresent() { return daysPresent; }
    public long getDaysAbsent() { return daysAbsent; }
    public long getApprovedLeaveDays() { return approvedLeaveDays; }
    public double getAttendancePercentage() { return attendancePercentage; }
}
