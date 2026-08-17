package com.indraacademy.ias_management.dto;

import java.util.List;

/**
 * One student who has been absent on every one of the most recent N *marked school days*
 * for their class — the "absent for the last 3 days" pattern, as opposed to
 * ClassAttendanceSummaryDTO's cumulative percentage view.
 *
 * Carries both halves deliberately: the streak fields answer "how recently and how long",
 * while the cumulative fields (mirroring ClassAttendanceSummaryDTO, and sourced from the very
 * same AttendanceService.getClassSummary computation) answer "and how are they doing overall".
 * A student can easily be absent three days running while still sitting above the school's
 * attendance threshold, so neither view substitutes for the other.
 *
 * absentDates are real marked school days, never a naive calendar countback — see
 * AttendanceService.getConsecutiveAbsentees for why that distinction is load-bearing.
 */
public class ConsecutiveAbsenceDTO {

    private String studentId;
    private String studentName;
    private String className;

    /** Length of the student's unbroken absence streak counting back from the most recent marked school day. */
    private int consecutiveAbsentDays;

    /** The actual dates making up that streak (ISO yyyy-MM-dd), oldest first. */
    private List<String> absentDates;

    // Cumulative session context — same numbers the percentage-based view reports.
    private long totalWorkingDays;
    private long daysPresent;
    private long daysAbsent;
    private double attendancePercentage;

    public ConsecutiveAbsenceDTO(String studentId, String studentName, String className,
                                 int consecutiveAbsentDays, List<String> absentDates,
                                 long totalWorkingDays, long daysPresent,
                                 long daysAbsent, double attendancePercentage) {
        this.studentId = studentId;
        this.studentName = studentName;
        this.className = className;
        this.consecutiveAbsentDays = consecutiveAbsentDays;
        this.absentDates = absentDates;
        this.totalWorkingDays = totalWorkingDays;
        this.daysPresent = daysPresent;
        this.daysAbsent = daysAbsent;
        this.attendancePercentage = attendancePercentage;
    }

    public String getStudentId() { return studentId; }
    public String getStudentName() { return studentName; }
    public String getClassName() { return className; }
    public int getConsecutiveAbsentDays() { return consecutiveAbsentDays; }
    public List<String> getAbsentDates() { return absentDates; }
    public long getTotalWorkingDays() { return totalWorkingDays; }
    public long getDaysPresent() { return daysPresent; }
    public long getDaysAbsent() { return daysAbsent; }
    public double getAttendancePercentage() { return attendancePercentage; }
}
